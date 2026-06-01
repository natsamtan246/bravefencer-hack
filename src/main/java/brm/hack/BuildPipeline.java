package brm.hack;

public class BuildPipeline {

    public static void main(String[] args) throws Exception {

        System.out.println("=== BRAVE FENCER TOOLCHAIN ===");

        // 1. Dump scripts
        System.out.println("[1] Dumping scripts...");
        // TODO: hook Dump.main logic here

        // 2. Rebuild CD
        System.out.println("[2] Rebuilding CD...");
        // TODO: call CdRebuilder.rebuild(...)

        // 3. Finish
        System.out.println("[DONE]");
    }
}