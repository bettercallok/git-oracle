package com.gitoracle.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

@RestController
public class TestRunnerController {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnerController.class);

    @PostMapping("/test")
    public TestResult runTest(@RequestBody TestRequest request) throws Exception {
        logger.info("Received request to run tests for job: {}", request.getJobId());
        logger.info("Identified framework: {}", request.getFramework());
        
        // Approach A: MOCK Docker Execution using Virtual Threads
        // TODO (Approach B): Wire up docker-java client, mount volume, and run command in container
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TestResult> future = executor.submit(() -> runMockContainer(request));
            // 120s hard timeout to prevent runaway tests
            return future.get(120, TimeUnit.SECONDS);
        }
    }

    private TestResult runMockContainer(TestRequest request) {
        try {
            logger.info("Spinning up mock Docker container...");
            // Simulate container startup and test execution delay
            Thread.sleep(2000);
            
            logger.info("Executing mock command: {}", request.getFramework().getCommand());
            logger.info("Streaming logs to Redis pubsub...");
            
            String mockLogs = "================ test session starts ================\n" +
                              "collecting ... collected 3 items\n\n" +
                              "test_agent.py::test_fix PASSED\n" +
                              "test_agent.py::test_plan PASSED\n" +
                              "test_agent.py::test_investigate PASSED\n\n" +
                              "================ 3 passed in 1.12s ================";
                              
            logger.info("Destroying mock Docker container.");
            
            // Return dummy successful result
            return new TestResult(true, 1.0, 0.05, mockLogs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(false, 0.0, 0.0, "Test execution interrupted.");
        }
    }
}
