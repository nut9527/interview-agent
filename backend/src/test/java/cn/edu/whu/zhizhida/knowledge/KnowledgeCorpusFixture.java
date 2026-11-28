package cn.edu.whu.zhizhida.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Loads the expanded, source-tracked corpus without changing the historical retrieval fixture. */
final class KnowledgeCorpusFixture {
    static final String RESOURCE = "/rag-corpus/manifest.json";

    private KnowledgeCorpusFixture() {}

    static JsonNode load(JsonMapper json, String resource) throws Exception {
        byte[] bytes = read(resource);
        var manifest = json.readTree(bytes);
        if (!RESOURCE.equals(resource)) return manifest;
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        var documents = json.createArrayNode();
        var cases = json.createArrayNode();
        for (var group : manifest.path("groups")) {
            String path = "/rag-corpus/" + group.asString();
            byte[] groupBytes = read(path);
            digest.update(groupBytes);
            var data = json.readTree(groupBytes);
            for (var item : data.path("documents")) {
                var document = (ObjectNode) item.deepCopy();
                String contentPath = item.path("resource").asString();
                byte[] content = read(contentPath);
                digest.update(content);
                document.put("content", new String(content, StandardCharsets.UTF_8));
                document.put("resource", contentPath);
                documents.add(document);
            }
            data.path("cases").forEach(cases::add);
        }
        var result = json.createObjectNode();
        result.set("manifest", manifest);
        result.put("fixtureSha256", HexFormat.of().formatHex(digest.digest()));
        result.set("documents", documents);
        result.set("cases", cases);
        return result;
    }

    private static byte[] read(String resource) throws IOException {
        try (var input = KnowledgeCorpusFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing fixture resource: " + resource);
            return input.readAllBytes();
        }
    }
}
