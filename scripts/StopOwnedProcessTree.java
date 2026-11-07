import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Windows fallback when taskkill cannot enumerate a process tree in the current session. */
class StopOwnedProcessTree {
    public static void main(String[] args) throws Exception {
        ProcessHandle child = ProcessHandle.of(Long.parseLong(args[0])).orElse(null);
        if (child == null) return;
        // Both this helper and the target must be direct children of the same launcher.
        ProcessHandle self = ProcessHandle.current();
        var launcher = self.parent();
        if (child.equals(self) || launcher.isEmpty() || !child.parent().equals(launcher)) {
            System.exit(1);
        }
        var tree = new ArrayList<>(child.descendants().toList());
        tree.addFirst(child);
        tree.forEach(ProcessHandle::destroyForcibly);
        CompletableFuture.allOf(tree.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
                .get(2, TimeUnit.SECONDS);
    }
}
