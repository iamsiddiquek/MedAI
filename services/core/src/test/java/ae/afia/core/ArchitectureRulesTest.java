package ae.afia.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture fitness checks that run with the unit tests.
 * 1. A module's SQL touches only its own schema, so modules stay extractable.
 * 2. Core never depends on a model provider or router; only model-gateway may.
 */
class ArchitectureRulesTest {

    private static final Path MAIN = Path.of("src/main/java/ae/afia/core");
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+(patient|encounter|media|transcript|note|audit|platform)\\.");
    private static final Pattern PROVIDER_IMPORT = Pattern.compile(
            "import\\s+(com\\.openai|com\\.anthropic|dev\\.langchain4j|org\\.springframework\\.ai|com\\.azure\\.ai"
                    + "|com\\.google\\.cloud\\.vertexai|software\\.amazon\\.awssdk\\.services\\.bedrock)");

    @Test
    void eachModuleWritesAndReadsOnlyItsOwnSchema() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String module = MAIN.relativize(file).getName(0).toString();
            Matcher matcher = TABLE_REF.matcher(Files.readString(file));
            while (matcher.find()) {
                if (!matcher.group(1).equalsIgnoreCase(module)) {
                    violations.add(file + " uses schema " + matcher.group(1));
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void coreHasNoModelProviderDependency() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            if (PROVIDER_IMPORT.matcher(Files.readString(file)).find()) {
                violations.add(file.toString());
            }
        }
        assertThat(violations).isEmpty();
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> list = files.filter(p -> p.toString().endsWith(".java") && p.getNameCount() > MAIN.getNameCount() + 1)
                    .toList();
            assertThat(list).isNotEmpty();
            return list;
        }
    }
}
