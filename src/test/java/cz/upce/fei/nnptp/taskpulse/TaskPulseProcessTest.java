package cz.upce.fei.nnptp.taskpulse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPulseProcessTest {

    @TempDir
    Path workingDirectory;

    @Test
    void createsAndListsProjectThroughStandardInput() throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName);
        Path applicationClasses = Path.of(TaskPulseApp.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path stdoutFile = workingDirectory.resolve("stdout.txt");
        Path stderrFile = workingDirectory.resolve("stderr.txt");

        Process application = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-cp", applicationClasses.toString(),
                TaskPulseApp.class.getName())
                .directory(workingDirectory.toFile())
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile())
                .start();

        try {
            try (OutputStream input = application.getOutputStream()) {
                input.write("""
                        project add "Programming course" "Semester project"
                        project list
                        exit
                        """.getBytes(StandardCharsets.UTF_8));
            }

            assertThat(application.waitFor(30, TimeUnit.SECONDS))
                    .as("CLI process should exit within 30 seconds")
                    .isTrue();

            String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
            String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
            assertThat(application.exitValue())
                    .as("CLI exit code; stdout: %s; stderr: %s", stdout, stderr)
                    .isZero();
            assertThat(stderr).isEmpty();

            Matcher createdProject = Pattern.compile(
                    "Project created: ([0-9a-f-]{36}) - Programming course").matcher(stdout);
            assertThat(createdProject.find())
                    .as("CLI should report the newly created project; stdout: %s", stdout)
                    .isTrue();
            UUID projectId = UUID.fromString(createdProject.group(1));
            assertThat(stdout.lines().toList())
                    .contains(projectId + " | Programming course | Semester project");
        } finally {
            if (application.isAlive()) {
                application.destroyForcibly();
                application.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
