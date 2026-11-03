package ai.gitoracle.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every bean has a constructor Spring can choose without guessing.
 *
 * <p>PrStatusReconciler shipped with two public constructors (one for Spring,
 * one for tests) and no {@code @Autowired}; Spring found no default constructor
 * and the orchestrator crash-looped on startup. Every unit test passed, because
 * none of them builds the application context — which needs Postgres and
 * Kafka. This checks the one rule that broke, without either.
 */
class BeanConstructorTest {

    @Test
    void everyBeanHasExactlyOneConstructorSpringCanPick() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));   // also @Service, @RestController, ...

        List<String> ambiguous = new ArrayList<>();
        for (var definition : scanner.findCandidateComponents("ai.gitoracle.orchestrator")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) continue;

            long autowired = Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            boolean hasNoArg = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            long nonPrivate = Arrays.stream(constructors).filter(c -> !Modifier.isPrivate(c.getModifiers())).count();
            if (autowired != 1 && !hasNoArg && nonPrivate > 1) {
                ambiguous.add(type.getName());
            }
        }

        assertThat(ambiguous)
            .as("beans with several constructors and none marked @Autowired — Spring can't start them")
            .isEmpty();
    }
}
