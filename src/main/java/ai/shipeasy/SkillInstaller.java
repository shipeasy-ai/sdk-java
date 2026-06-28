package ai.shipeasy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@code SkillInstaller} — install the bundled Shipeasy agent skill into a
 * project. The JVM has no safe post-install hook, so shipping the skill is an
 * explicit, opt-in command. The skill ships inside the jar as the classpath
 * resource {@code /shipeasy-skill/SKILL.md} (kept in sync with
 * {@code docs/skill/SKILL.md} by {@code tools/GenReadme.java}):
 *
 * <pre>{@code
 * java -cp shipeasy.jar ai.shipeasy.SkillInstaller install              # -> .claude/skills/shipeasy-java/SKILL.md
 * java -cp shipeasy.jar ai.shipeasy.SkillInstaller install --dir path/  # custom destination (file or dir)
 * java -cp shipeasy.jar ai.shipeasy.SkillInstaller install --force      # overwrite an existing file
 * java -cp shipeasy.jar ai.shipeasy.SkillInstaller print                # write the skill to stdout
 * }</pre>
 */
public final class SkillInstaller {
    private static final String RESOURCE = "/shipeasy-skill/SKILL.md";
    private static final String DEFAULT_DEST = ".claude/skills/shipeasy-java/SKILL.md";

    private SkillInstaller() {}

    static String skillText() {
        try (InputStream in = SkillInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("bundled SKILL.md not found on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bundled SKILL.md", e);
        }
    }

    static int install(String dir, boolean force) throws IOException {
        Path dest = Paths.get(dir);
        boolean looksDir = Files.isDirectory(dest) || !dest.getFileName().toString().contains(".");
        if (looksDir) dest = dest.resolve("SKILL.md");
        if (Files.exists(dest) && !force) {
            System.err.println("shipeasy-skill: refusing to overwrite " + dest + " — pass --force");
            return 1;
        }
        if (dest.getParent() != null) Files.createDirectories(dest.getParent());
        Files.writeString(dest, skillText());
        System.out.println("shipeasy-skill: installed the Shipeasy agent skill → " + dest);
        return 0;
    }

    public static void main(String[] args) throws IOException {
        String cmd = args.length > 0 ? args[0] : null;
        if ("print".equals(cmd)) {
            System.out.print(skillText());
            return;
        }
        if ("install".equals(cmd)) {
            String dir = DEFAULT_DEST;
            boolean force = false;
            for (int i = 1; i < args.length; i++) {
                if ("--force".equals(args[i])) force = true;
                else if ("--dir".equals(args[i]) && i + 1 < args.length) dir = args[++i];
            }
            System.exit(install(dir, force));
            return;
        }
        System.out.println("shipeasy-skill — install the Shipeasy agent skill.\n\n"
            + "  ai.shipeasy.SkillInstaller install [--dir <path>] [--force]\n"
            + "  ai.shipeasy.SkillInstaller print");
        if (cmd != null && !cmd.equals("--help") && !cmd.equals("-h")) System.exit(1);
    }
}
