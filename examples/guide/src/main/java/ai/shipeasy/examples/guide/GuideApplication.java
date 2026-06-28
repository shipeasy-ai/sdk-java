package ai.shipeasy.examples.guide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Shipeasy Java Entity Guide example.
 *
 * <p>Run it with {@code ./mvnw spring-boot:run} and open
 * <a href="http://localhost:8080">http://localhost:8080</a>.
 *
 * <p>The app renders one card per Shipeasy entity. Every value is a hardcoded
 * placeholder right now — the SDK ({@code ai.shipeasy:shipeasy}) is NOT a
 * dependency yet. See {@link GuideController} for the {@code // TODO} blocks
 * that show the real SDK call to drop in once it is installed.
 */
@SpringBootApplication
public class GuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuideApplication.class, args);
    }
}
