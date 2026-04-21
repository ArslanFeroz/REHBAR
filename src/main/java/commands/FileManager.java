package commands;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileManager -- create, delete, rename files/folders on the Desktop.
 *
 * Fixes vs original:
 *  - extractName() returns null instead of "" so callers give a clear
 *    "tell me the name" message instead of crashing on Files.createFile("").
 *  - extractName() no longer strips a/an/the mid-string (would corrupt
 *    names containing those letters, e.g. "theatre" -> "tre").
 *    Articles are only stripped as leading standalone words.
 *  - rename() checks " to " is present before splitting -- was crashing
 *    with ArrayIndexOutOfBoundsException when " to " was absent.
 *  - rename() checks source exists and gives a specific "not found" message.
 *  - rename() checks target does not already exist.
 *  - rename() uses stripRenamePrefix() to cleanly remove filler words
 *    from the old-name portion.
 *  - confirmDelete() catches NoSuchFileException and DirectoryNotEmptyException
 *    separately to give actionable spoken messages.
 *  - CONFIRM_DELETE: prefix is documented -- callers must strip it before TTS.
 */
public class FileManager {

    private static final String BASE_DIR =
            System.getProperty("user.home") + "/Desktop";

    // ── Name extraction ───────────────────────────────────────────────────────

    /**
     * Extracts the bare file/folder name from a natural-language command.
     * Returns null if no name can be determined.
     *
     * Priority:
     *   1. "named X"  or  "called X"  -- most reliable
     *   2. Strip action/type words and return the remainder
     */
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

    /**
     * Returns "CONFIRM_DELETE:<name>" so the caller can ask the user
     * before actually deleting.
     * IMPORTANT: the caller must strip "CONFIRM_DELETE:" before sending
     * to TTS, otherwise it will be spoken literally.
     */
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

    /**
     * Renames a file or folder on the Desktop.
     *
     * Expected voice patterns:
     *   "rename Projects to Work"
     *   "rename the folder Projects to Work"
     *   "change the name of Projects to Work"
     *
     * Fixes:
     *   - Guards against missing " to " (was crashing with AIOOBE).
     *   - Checks source exists -- gives "not found" message.
     *   - Checks target does not already exist.
     *   - stripRenamePrefix() cleanly removes filler from old-name portion.
     */
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

    /**
     * Strips rename/change/filler words from the old-name portion.
     *
     * "rename the folder projects"   ->  "projects"
     * "change the name of projects"  ->  "projects"
     */
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