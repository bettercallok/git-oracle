package com.gitoracle.testrunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.LogContainerResultCallback;

import com.github.dockerjava.api.model.Capability;
import java.util.Arrays;
import java.util.List;

@RestController
public class TestRunnerController {

    private static final Logger logger = LoggerFactory.getLogger(TestRunnerController.class);
    private final DockerClient dockerClient;

    public TestRunnerController() {
        this.dockerClient = DockerClientBuilder.getInstance().build();
    }

    @PostMapping("/test")
    public TestResult runTest(@RequestBody TestRequest request) throws Exception {
        logger.info("Received request to run tests for job: {}", request.getJobId());
        logger.info("Identified framework: {}", request.getFramework());
        
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<TestResult> future = executor.submit(() -> runRealContainer(request));
            // 120s hard timeout to prevent runaway tests
            return future.get(120, TimeUnit.SECONDS);
        }
    }

    private TestResult runRealContainer(TestRequest request) {
        String containerId = null;
        try {
            logger.info("Pulling docker image python:3.11-alpine...");
            dockerClient.pullImageCmd("python:3.11-alpine").start().awaitCompletion();
            
            logger.info("Creating secure Docker container...");
            String repoPath = "/Users/omkhatri/Git Oracle"; // Ideally passed dynamically
            
            HostConfig hostConfig = HostConfig.newHostConfig()
                .withNetworkMode("none")           // no network access
                .withMemory(512 * 1024 * 1024L)    // 512MB RAM limit
                .withCpuCount(2L)                  // 2 CPU cores max
                .withReadonlyRootfs(false)         // allow writes for test build
                .withCapDrop(Capability.ALL)       // drop all Linux capabilities
                .withSecurityOpts(List.of("no-new-privileges"))
                .withBinds(new Bind(repoPath, new Volume("/repo"), AccessMode.rw));

            CreateContainerResponse container = dockerClient.createContainerCmd("python:3.11-alpine")
                .withHostConfig(hostConfig)
                .withWorkingDir("/repo")
                .withCmd("sh", "-c", request.getFramework().getCommand())
                .exec();

            containerId = container.getId();
            
            logger.info("Starting container: {}", containerId);
            dockerClient.startContainerCmd(containerId).exec();
            
            logger.info("Waiting for container to finish execution...");
            Integer exitCode = dockerClient.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode();
                
            logger.info("Container finished with exit code: {}", exitCode);
            
            StringBuilder logBuilder = new StringBuilder();
            dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .exec(new LogContainerResultCallback() {
                    @Override
                    public void onNext(Frame item) {
                        logBuilder.append(new String(item.getPayload()));
                    }
                }).awaitCompletion();

            String logs = logBuilder.toString();
            boolean success = (exitCode == 0);
            
            return new TestResult(success, success ? 1.0 : 0.0, 0.0, logs);
        } catch (Exception e) {
            logger.error("Error executing Docker container", e);
            return new TestResult(false, 0.0, 0.0, "Test execution failed: " + e.getMessage());
        } finally {
            if (containerId != null) {
                logger.info("Removing Docker container: {}", containerId);
                try {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                } catch (Exception e) {
                    logger.warn("Failed to remove container: {}", containerId, e);
                }
            }
        }
    }
}
