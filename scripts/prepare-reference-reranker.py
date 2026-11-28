"""按固定版本下载 ONNX 重排模型和分词器，校验后存入项目缓存。"""
import hashlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Xenova/bge-reranker-base"
REVISION = "280bcc27a84e0b898c251e06fddb25171bd9b101"
MODEL_DIR = ROOT / ".cache/reranker" / REVISION
FILES = {
    "onnx/model_quantized.onnx": "dd98f3e67837d23210a6b7550c08cced4f61845b940ac45be3565840a10f3244",
    "tokenizer.json": "48564c5c7d3fa64d85d95e65414a542385f88b0f128fd8d4163fd7a57f2be05c",
}
SIZES = {"onnx/model_quantized.onnx": 279301077, "tokenizer.json": 17098079}


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare():
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for name, expected in FILES.items():
        target = MODEL_DIR / Path(name).name
        if target.exists():
            assert sha256(target) == expected, target
            print(f"Verified {target.name}", flush=True)
            continue
        import httpx
        partial = target.with_suffix(".part")
        for attempt in (1, 2):
            started = time.monotonic()
            try:
                size = SIZES[name]
                url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{name}?download=true"

                def fetch(start):
                    end = min(start + 4 * 1024 * 1024, size) - 1
                    for transfer in (1, 2):
                        try:
                            with client.stream("GET", url, headers={"Range": f"bytes={start}-{end}"}) as response:
                                response.raise_for_status()
                                assert response.headers["Content-Range"] == f"bytes {start}-{end}/{size}"
                                blocks = []
                                for block in response.iter_bytes(65536):
                                    blocks.append(block)
                                    if time.monotonic() - started > 300:
                                        raise TimeoutError(f"Five-minute download limit: {name}")
                            data = b"".join(blocks)
                            assert len(data) == end - start + 1
                            return data
                        except httpx.TransportError:
                            if transfer == 2:
                                raise

                with httpx.Client(timeout=30, follow_redirects=True, limits=httpx.Limits(max_connections=8)) as client, \
                        ThreadPoolExecutor(max_workers=8) as pool, partial.open("wb") as output:
                    for index, data in enumerate(pool.map(fetch, range(0, size, 4 * 1024 * 1024))):
                        output.write(data)
                        if index % 8 == 0:
                            print(f"{target.name}: {output.tell() // 1048576} MiB", flush=True)
                assert sha256(partial) == expected, name
                partial.rename(target)
                print(f"Downloaded and verified {target.name}", flush=True)
                break
            except TimeoutError:
                if attempt == 2:
                    raise
                print(f"Retrying timed-out download: {name}", flush=True)
            finally:
                partial.unlink(missing_ok=True)


if __name__ == "__main__":
    prepare()
