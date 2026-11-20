package cn.edu.whu.zhizhida.knowledge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import cn.edu.whu.zhizhida.common.BusinessException;
import cn.edu.whu.zhizhida.common.UserInputException;
import cn.edu.whu.zhizhida.knowledge.KnowledgeImportRepository.Claim;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/** 两种派发方式共用解析与写回；本地文件由受理进程管理，共享文件可在租约失效后重领。 */
@Service
public class KnowledgeImportExecutor {
    private final KnowledgeImportRepository repository;
    private final KnowledgeDocumentImporter importer;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final Path root;
    private final long timeout;
    private final boolean shared;
    private final boolean enabled;
    private final ConcurrentMap<String, Work> running = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Claim> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon(true).name("knowledge-import-", 0).inheritInheritableThreadLocals(false)
                    .factory());
    private final ScheduledExecutorService leaseTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("import-lease").inheritInheritableThreadLocals(false).factory());

    private record Work(Claim claim, Path directory, FutureTask<Void> future) {
    }

    public KnowledgeImportExecutor(KnowledgeImportRepository repository, KnowledgeDocumentImporter importer,
            ObjectMapper json, MeterRegistry metrics, Environment env) {
        this.repository = repository;
        this.importer = importer;
        this.json = json;
        this.metrics = metrics;
        shared = "rabbitmq".equalsIgnoreCase(env.getProperty("knowledge.dispatch.mode"));
        enabled = env.getProperty("knowledge.import.worker-enabled", Boolean.class, true);
        root = Path.of(shared ? env.getProperty("knowledge.import.shared-directory", "tmp/knowledge-shared")
                : env.getProperty("knowledge.import.directory", "tmp/knowledge-import-jobs")).toAbsolutePath()
                .normalize();
        timeout = env.getProperty("knowledge.import.timeout-seconds", Long.class, 180L);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() throws IOException {
        if (!shared && enabled) {
            Files.createDirectories(root);
            repository.recover();
            cleanAbandoned();
        }
        if (shared || enabled)
            leaseTimer.scheduleWithFixedDelay(this::checkLeases, 5, 5, TimeUnit.SECONDS);
    }

    synchronized String submit(long owner, MultipartFile file) {
        if (!shared && !enabled)
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "当前进程不受理资料导入。");
        KnowledgeDocumentImporter.validate(file);
        if (!shared) {
            if (repository.outstanding(owner) >= 3)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "最多保留 3 个待完成导入，请先核对或取消已有任务。");
            if (running.size() >= 6)
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "解析队列已满，请稍后重试。");
        }
        String id = UUID.randomUUID().toString(),
                name = KnowledgeDocumentImporter.sourceName(file.getOriginalFilename());
        Path directory = null;
        try {
            Files.createDirectories(root);
            directory = Files.createDirectory(root.resolve(shared ? id : "job-" + id));
            Path source = directory.resolve("source.upload");
            if (shared) {
                Path pending = directory.resolve("upload.part");
                file.transferTo(pending);
                try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                Files.move(pending, source, StandardCopyOption.ATOMIC_MOVE);
                repository.enqueue(owner, id, name, id + "/source.upload", timeout);
            } else {
                file.transferTo(source);
                repository.insert(owner, id, name, Instant.now().plusSeconds(1800));
                scheduleLocal(repository.localClaim(id, owner, name, Instant.now().plusSeconds(timeout)), directory);
            }
            return id;
        } catch (IOException ex) {
            clean(directory);
            throw new BusinessException(shared ? "无法持久保存上传文件，请重试。" : "无法暂存上传文件，请重试。");
        } catch (RuntimeException ex) {
            // 共享文件的数据库提交结果有可能未知，交给维护任务核对后清理。
            if (!shared || ex instanceof BusinessException)
                clean(directory);
            throw ex;
        }
    }

    private void scheduleLocal(Claim claim, Path directory) {
        var task = new FutureTask<Void>(() -> {
            execute(claim);
            return null;
        }) {
            @Override
            public void run() {
                try {
                    super.run();
                } finally {
                    running.remove(claim.id());
                    clean(directory);
                }
            }
        };
        running.put(claim.id(), new Work(claim, directory, task));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException ex) {
            running.remove(claim.id());
            repository.stop(claim.owner(), claim.id(), "FAILED", "解析队列已满，请重新上传。");
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "解析队列已满，请稍后重试。");
        }
    }

    public void execute(String id) {
        repository.claim(id).ifPresent(this::execute);
    }

    private void execute(Claim claim) {
        if (shared)
            active.put(claim.token(), claim);
        var sample = io.micrometer.core.instrument.Timer.start(metrics);
        long[] checked = { 0 };
        Runnable checkpoint = () -> {
            if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(claim.deadline())
                    || (shared && !active.containsKey(claim.token())))
                throw new CancellationException();
            if (!shared && System.nanoTime() - checked[0] > 500_000_000L) {
                checked[0] = System.nanoTime();
                if (!repository.active(claim.owner(), claim.id()))
                    throw new CancellationException();
            }
        };
        BiConsumer<Integer, Integer> progress = (done, total) -> {
            checkpoint.run();
            if (done == 0 || done.equals(total) || done % 10 == 0)
                repository.progress(claim, done, total);
        };
        try {
            if (!shared && !repository.start(claim.id()))
                return;
            var draft = shared ? importer.parseQueued(source(claim), claim.name(), progress, checkpoint)
                    : importer.parse(root.resolve("job-" + claim.id()).resolve("source.upload"), claim.name(), progress,
                            checkpoint);
            checkpoint.run();
            repository.ready(claim, json.writeValueAsString(draft));
        } catch (UserInputException ex) {
            failed(claim, ex.getMessage(), false);
        } catch (CancellationException ex) {
            failed(claim, shared ? "执行中断，等待重试。" : "解析已取消或超时，请重新上传。", true);
        } catch (Exception ex) {
            failed(claim, shared ? "文件暂时无法解析，等待重试；请检查共享文件存储。" : "文件解析失败，请检查格式或拆分文件后重试。", true);
        } finally {
            if (shared)
                active.remove(claim.token());
            sample.stop(metrics.timer("knowledge.import.execution"));
        }
    }

    private void failed(Claim claim, String message, boolean retry) {
        if (shared)
            repository.failed(claim, message, retry);
        else
            repository.stop(claim.owner(), claim.id(), "FAILED", message);
    }

    private Path source(Claim c) throws IOException {
        if (!c.fileRef().equals(c.id() + "/source.upload"))
            throw new IOException("Invalid file reference");
        Path path = root.resolve(c.fileRef()).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path.getParent()) || Files.isSymbolicLink(path)
                || !path.toRealPath().startsWith(root.toRealPath()) || !Files.isRegularFile(path))
            throw new IOException("Invalid source");
        return path;
    }

    private void renew() {
        for (var c : active.values()) {
            try {
                if (!repository.renew(c))
                    active.remove(c.token());
            } catch (RuntimeException ex) {
                active.remove(c.token());
                metrics.counter("knowledge.import.lease", "outcome", "unavailable").increment();
            }
        }
    }

    public void checkLeases() {
        if (shared)
            renew();
        else if (enabled)
            sweep();
    }

    public void cancel(String id) {
        var work = running.get(id);
        if (work == null)
            return;
        boolean queued = workers.remove(work.future());
        work.future().cancel(true);
        if (queued && running.remove(id, work))
            clean(work.directory());
    }

    void sweep() {
        try {
            repository.recover();
            for (var work : running.values()) {
                var claim = work.claim();
                if (!Instant.now().isBefore(claim.deadline()) || !repository.renew(claim.id())) {
                    repository.stop(claim.owner(), claim.id(), "FAILED", "解析超过 3 分钟或账号已删除，请拆分文件后重试。");
                    cancel(claim.id());
                }
            }
            repository.expire();
            cleanAbandoned();
        } catch (Exception ex) {
            metrics.counter("knowledge.import.maintenance", "outcome", "unavailable").increment();
        }
    }

    private void cleanAbandoned() throws IOException {
        if (!Files.isDirectory(root))
            return;
        try (var paths = Files.list(root)) {
            for (var path : paths.filter(p -> p.getFileName().toString().matches("job-[0-9a-f-]{36}")).toList()) {
                String id = path.getFileName().toString().substring(4);
                // 宽限期覆盖创建文件到登记数据库之间的短暂窗口。
                if (!running.containsKey(id)
                        && Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minusSeconds(30))
                        && !repository.processing(id))
                    clean(path);
            }
        }
    }

    private void clean(Path directory) {
        // 清理仅接受导入根目录的直接子目录，并拒绝符号链接。
        if (directory == null || !directory.toAbsolutePath().normalize().getParent().equals(root)
                || Files.isSymbolicLink(directory))
            return;
        try {
            if (shared) {
                Files.deleteIfExists(directory.resolve("source.upload"));
                Files.deleteIfExists(directory.resolve("upload.part"));
                Files.deleteIfExists(directory);
            } else
                try (var files = Files.walk(directory)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(file);
                }
        } catch (DirectoryNotEmptyException ignored) {
            /* 共享目录内的其他文件保留给所有者。 */ } catch (IOException ex) {
            metrics.counter("knowledge.import.maintenance", "outcome", "unavailable").increment();
        }
    }

    @Scheduled(fixedDelayString = "${knowledge.import.maintenance-ms:5000}")
    public void maintain() {
        if (!shared)
            return;
        try {
            repository.recoverShared();
            if (!Files.isDirectory(root))
                return;
            try (var paths = Files.list(root)) {
                for (var directory : paths.filter(p -> p.getFileName().toString()
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")).toList()) {
                    // 宽限期覆盖慢上传尚未登记数据库的窗口，避免清理仍在受理的文件。
                    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)
                            || !Files.getLastModifiedTime(directory).toInstant()
                                    .isBefore(Instant.now().minusSeconds(1800))
                            || repository.retainsFile(directory.getFileName().toString()))
                        continue;
                    // 只删除本模块创建的两种文件；含未知内容的目录保留。
                    clean(directory);
                }
            }
        } catch (Exception ex) {
            metrics.counter("knowledge.import.maintenance", "outcome", "unavailable").increment();
        }
    }

    @PreDestroy
    public void close() {
        leaseTimer.shutdownNow();
        for (var work : running.values()) {
            var claim = work.claim();
            repository.stop(claim.owner(), claim.id(), "FAILED", "服务停止中断了解析，请重新上传。");
            cancel(claim.id());
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
