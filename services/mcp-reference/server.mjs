import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { search } from './catalog.mjs';

const protocolVersion = '2025-06-18';
const tool = {
  name: 'reference_search',
  description: 'Find technical reading notes and primary sources in the local interview reference catalog.',
  inputSchema: {
    type: 'object',
    properties: { query: { type: 'string' }, topK: { type: 'integer' } },
    required: ['query', 'topK'], additionalProperties: false,
  },
};

function reply(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
}

function handle(request, res) {
  const { id, method, params } = request ?? {};
  const error = (code, message) => reply(res, 200, { jsonrpc: '2.0', id: id ?? null, error: { code, message } });
  if (request?.jsonrpc !== '2.0' || typeof method !== 'string') return error(-32600, 'Invalid request');
  if (id === undefined) { res.writeHead(202).end(); return; }
  let result;
  switch (method) {
    case 'initialize':
      result = { protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'interview-references', version: '1' } };
      break;
    case 'ping': result = {}; break;
    case 'tools/list': result = { tools: [tool] }; break;
    case 'tools/call': {
      const args = params?.arguments;
      if (params?.name !== tool.name || Object.keys(args ?? {}).length !== 2
        || typeof args?.query !== 'string' || !args.query.trim() || args.query.length > 300
        || !Number.isInteger(args.topK) || args.topK < 1 || args.topK > 3) {
        return error(-32602, 'Invalid tool or arguments');
      }
      result = { content: [{ type: 'text', text: JSON.stringify(search(args.query, args.topK)) }] };
      break;
    }
    default: return error(-32601, 'Method not found');
  }
  reply(res, 200, { jsonrpc: '2.0', id, result });
}

export function referenceServer() {
  return createServer(async (req, res) => {
    if (req.headers.origin) { res.writeHead(403).end(); return; } // For backend clients only.
    if (req.method === 'GET' && req.url === '/health') { reply(res, 200, { status: 'UP' }); return; }
    if (req.url !== '/mcp') { res.writeHead(404).end(); return; }
    if (req.method !== 'POST') { res.writeHead(405, { Allow: 'POST' }).end(); return; }
    if (req.headers['mcp-protocol-version'] && req.headers['mcp-protocol-version'] !== protocolVersion) {
      res.writeHead(400).end(); return;
    }
    const chunks = [];
    let size = 0;
    try {
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 8192) { res.writeHead(413).end(); return; }
        chunks.push(chunk);
      }
      handle(JSON.parse(Buffer.concat(chunks).toString('utf8')), res);
    } catch {
      reply(res, 400, { jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON' } });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  referenceServer().listen(Number(process.env.MCP_PORT ?? 18091), process.env.MCP_BIND_HOST ?? '127.0.0.1');
}
