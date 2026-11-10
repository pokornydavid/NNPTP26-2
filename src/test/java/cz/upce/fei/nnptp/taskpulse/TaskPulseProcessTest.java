package cz.upce.fei.nnptp.taskpulse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPulseProcessTest {

    @TempDir
    Path workingDirectory;

    @Test
    void createsAndListsProjectThroughStandardInput() throws Exception {
        String stdout = runCli("""
                project add "Programming course" "Semester project"
                project list
                exit
                """);

        UUID projectId = createdId(stdout, "Project", "Programming course");
        assertThat(stdout.lines().toList())
                .contains(projectId + " | Programming course | Semester project");
    }

    @Test
    void completesTaskWithWorklogsAndPreservesItAcrossRestarts() throws Exception {
        String projectOutput = runCli("""
                project add "Development project"
                exit
                """);
        UUID projectId = createdId(projectOutput, "Project", "Development project");

        String taskOutput = runCli("""
                task add %s "Implement CSV export" "Export project data"
                exit
                """.formatted(projectId));
        UUID taskId = createdId(taskOutput, "Task", "Implement CSV export");

        String workflowOutput = runCli("""
                task transition %1$s IN_PROGRESS
                worklog log %1$s 45 --author "Student Developer" --desc "First implementation"
                worklog log %1$s 15 --author "Student Developer" --desc "Review and tests"
                task transition %1$s DONE
                exit
                """.formatted(taskId));
        assertThat(workflowOutput).containsSubsequence(
                "Task " + taskId + " transitioned to IN_PROGRESS",
                "Work logged: 45 min by Student Developer on task " + taskId,
                "Work logged: 15 min by Student Developer on task " + taskId,
                "Task " + taskId + " transitioned to DONE");

        String reloadedOutput = runCli("""
                task show %1$s
                worklog list %1$s
                exit
                """.formatted(taskId));
        assertThat(reloadedOutput.lines().toList()).contains(
                "ID:          " + taskId,
                "Project:     " + projectId,
                "Title:       Implement CSV export",
                "Description: Export project data",
                "State:       DONE",
                "Worklogs:    2 (60 min recorded)");
        assertThat(reloadedOutput).contains(
                " | 45 min | Student Developer | First implementation",
                " | 15 min | Student Developer | Review and tests");
    }

    @Test
    void exitsAndSavesProjectWhenStandardInputEnds() throws Exception {
        String stdout = runCli("""
                project add "EOF project" "Saved on EOF"
                """);

        UUID projectId = createdId(stdout, "Project", "EOF project");

        String reloadedOutput = runCli("""
                project list
                """);

        assertThat(reloadedOutput.lines().toList())
                .contains(projectId + " | EOF project | Saved on EOF");
    }

    @ParameterizedTest(name = "continues after {0}")
    @MethodSource("invalidCommands")
    void continuesAfterInvalidCommandAndReportsErrorOnStderr(String command, String expectedError)
            throws Exception {
        String stdout = runCli("""
                %s
                project add "Recovery project" "After invalid command"
                project list
                exit
                """.formatted(command), expectedError);

        UUID projectId = createdId(stdout, "Project", "Recovery project");
        assertThat(stdout.lines().toList())
                .contains(projectId + " | Recovery project | After invalid command");
        assertThat(stdout).doesNotContain(expectedError);
    }

    private static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of("unknownCommand",
                        "Unknown command: 'unknowncommand'. Type 'help' for available commands."),
                Arguments.of("project add", "Usage: project add <name> [description]"),
                Arguments.of("project show not-a-uuid",
                        "Invalid argument: Invalid UUID format: 'not-a-uuid'"),
                Arguments.of("project show 00000000-0000-0000-0000-000000000000",
                        "Error: Project not found with id: 00000000-0000-0000-0000-000000000000"),
                Arguments.of("task list --state invalid",
                        "Invalid argument: Invalid task state: 'invalid'. Allowed: [TODO, IN_PROGRESS, BLOCKED, DONE]")
        );
    }

    @Test
    void stopsReadingCommandsAfterExitAndSavesEarlierChanges() throws Exception {
        String stdout = runCli("""
                project add "Before exit" "Saved before exit"
                exit
                project add "After exit" "Must be ignored"
                """);
        UUID projectId = createdId(stdout, "Project", "Before exit");
        assertThat(stdout).doesNotContain("After exit");

        String reloadedOutput = runCli("""
                project list
                """);
        assertThat(reloadedOutput.lines().toList())
                .contains(projectId + " | Before exit | Saved before exit");
        assertThat(reloadedOutput).doesNotContain("After exit");
    }

    private UUID createdId(String stdout, String entity, String title) {
        Matcher createdEntity = Pattern.compile(Pattern.quote(entity + " created: ")
                + "([0-9a-f-]{36})" + Pattern.quote(" - " + title)).matcher(stdout);
        assertThat(createdEntity.find())
                .as("CLI should report the newly created %s; stdout: %s", entity, stdout)
                .isTrue();
        return UUID.fromString(createdEntity.group(1));
    }

    private String runCli(String commands) throws Exception {
        return runCli(commands, "");
    }

    private String runCli(String commands, String expectedError) throws Exception {
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
                input.write(commands.getBytes(StandardCharsets.UTF_8));
            }

            assertThat(application.waitFor(30, TimeUnit.SECONDS))
                    .as("CLI process should exit within 30 seconds")
                    .isTrue();

            String stdout = Files.readString(stdoutFile, StandardCharsets.UTF_8);
            String stderr = Files.readString(stderrFile, StandardCharsets.UTF_8);
            assertThat(application.exitValue())
                    .as("CLI exit code; stdout: %s; stderr: %s", stdout, stderr)
                    .isZero();
            assertThat(stderr.strip()).isEqualTo(expectedError);
            return stdout;
        } finally {
            if (application.isAlive()) {
                application.destroyForcibly();
                application.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
