package commands;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Creates, deletes, and renames files/folders on the Desktop with safe name extraction and confirm-delete prefix protocol. */
public class FileManager implements CommandHandler {

    private static final String BASE_DIR =
            System.getProperty("user.home") + "/Desktop";

    @Override
    public String execute(String input) { return create(input); }

    // ── Name extraction ───────────────────────────────────────────────────────

    /** Extracts bare name from command ("named X" / "called X" first, then strips action words); returns null if none found. */
    public String extractName(String command) {
        if (command == null || command.isBlank()) return null;

        String cmd = command.toLowerCase().trim();
        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        if (cmd.contains(" named ")) {
            String name = cmd.substring(cmd.indexOf(" named ") + 7).trim();
            return name.isEmpty() ? null : name;
        }
        if (cmd.contains(" called ")) {
            String name = cmd.substring(cmd.indexOf(" called ") + 8).trim();
            return name.isEmpty() ? null : name;
        }

        // Strip action and type words -- NOT articles mid-string
        cmd = cmd.replaceAll(
                "\\b(create|make|new|delete|remove|rename|open|launch|start|" +
                        "run|execute|a|an|the|folder|file|document|directory)\\b", " ");

        String result = cmd.trim().replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? null : result;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public String create(String command) {
        boolean isFolder = command.toLowerCase().contains("folder") ||
                command.toLowerCase().contains("directory");
        String name = extractName(command);

        if (name == null || name.isBlank()) {
            return "Please tell me the name of the " +
                    (isFolder ? "folder" : "file") + " you want to create.";
        }

        Path path = Paths.get(BASE_DIR, name);
        try {
            if (isFolder) Files.createDirectory(path);
            else          Files.createFile(path);
            return (isFolder ? "Folder" : "File") + " '" + name + "' created on Desktop.";
        } catch (FileAlreadyExistsException e) {
            return "A " + (isFolder ? "folder" : "file") +
                    " named '" + name + "' already exists on the Desktop.";
        } catch (IOException e) {
            return "Could not create '" + name +
                    "'. Check that the name is valid and you have permission.";
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Returns "CONFIRM_DELETE:<name>" so the caller can prompt before deleting; caller must strip prefix before TTS. */
    public String delete(String command) {
        String name = extractName(command);

        if (name == null || name.isBlank()) {
            return "Please tell me the name of the file or folder you want to delete.";
        }

        Path path = Paths.get(BASE_DIR, name);
        if (!Files.exists(path)) {
            return "I could not find '" + name + "' on your Desktop. " +
                    "Please check the name and try again.";
        }
        return "CONFIRM_DELETE:" + name;
    }

    public String confirmDelete(String name) {
        try {
            Files.delete(Paths.get(BASE_DIR, name));
            return "'" + name + "' has been deleted.";
        } catch (NoSuchFileException e) {
            return "'" + name + "' no longer exists.";
        } catch (DirectoryNotEmptyException e) {
            return "The folder '" + name +
                    "' is not empty. Please empty it first, then ask me to delete it.";
        } catch (IOException e) {
            return "Could not delete '" + name +
                    "'. It may be open in another program.";
        }
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    /** Renames a Desktop file/folder; guards against missing " to ", verifies source exists, and checks target is free. */
    public String rename(String command) {
        String cmd = command.toLowerCase().trim();

        if (!cmd.contains(" to ")) {
            return "Please say what you want to rename it to. " +
                    "For example: 'rename Projects to Work'.";
        }

        int toIdx   = cmd.lastIndexOf(" to ");
        String rawOld = cmd.substring(0, toIdx);
        String newName = cmd.substring(toIdx + 4).trim();

        String oldName = stripRenamePrefix(rawOld);

        if (oldName == null || oldName.isBlank()) {
            return "I could not figure out which file to rename. " +
                    "Try: 'rename Projects to Work'.";
        }
        if (newName.isBlank()) {
            return "Please tell me the new name. " +
                    "For example: 'rename Projects to Work'.";
        }

        Path source = Paths.get(BASE_DIR, oldName);
        Path target = Paths.get(BASE_DIR, newName);

        if (!Files.exists(source)) {
            return "I could not find '" + oldName + "' on your Desktop. " +
                    "Please check the name and try again.";
        }
        if (Files.exists(target)) {
            return "A file or folder named '" + newName +
                    "' already exists. Please choose a different name.";
        }

        try {
            Files.move(source, target);
            return "Renamed '" + oldName + "' to '" + newName + "'.";
        } catch (IOException e) {
            return "Could not rename '" + oldName +
                    "'. It may be open in another program. Please close it and try again.";
        }
    }

    /** Strips rename/change/filler words from the old-name portion to isolate the bare name. */
    private String stripRenamePrefix(String raw) {
        String s = raw.trim();
        s = s.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");
        s = s.replaceAll(
                "^(rename|change the name of|change name of|" +
                        "modify the name of|update the name of|relabel)\\s*", "");
        s = s.replaceAll(
                "\\b(the|my|this|a|an|folder|file|document|directory)\\b", " ");
        return s.trim().replaceAll("\\s+", " ").trim();
    }
}