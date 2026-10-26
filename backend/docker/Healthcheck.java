import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 运行时镜像已包含 Java，健康检查无需额外安装命令行下载工具。 */
public final class Healthcheck {
    public static void main(String[] args) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var request = HttpRequest.newBuilder(URI.create(args[0])).timeout(Duration.ofSeconds(4)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception ex) {
            System.exit(1);
        }
    }
}
