package commands;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileManager {
    private static final String BASE_DIR =
            System.getProperty("user.home") + "/Desktop";

    public String extractName(String command) {
        String cmd = command.toLowerCase().trim();

        // Remove polite filler words at the start
        cmd = cmd.replaceAll(
                "^(please|can you|could you|hey rahbar|rahbar)\\s+", "");

        // Case 1: "named [name]" or "called [name]"
        // Most specific — handle these first
        if (cmd.contains(" named ")) {
            return cmd.substring(cmd.indexOf(" named ") + 7).trim();
        }
        if (cmd.contains(" called ")) {
            return cmd.substring(cmd.indexOf(" called ") + 8).trim();
        }

        // Case 2: No keyword — strip action and type words
        // Removes: create, make, delete, remove, a, the, new, folder, file etc.
        cmd = cmd.replaceAll(
                "\\b(create|make|new|delete|remove|rename|open" +
                        "|a|an|the|folder|file|document|directory)\\b", "");

        // Clean up leftover spaces
        return cmd.trim().replaceAll("\\s+", " ");
    }




    // Create File or Folder
    public String create(String command) {
        // Parse: 'create folder named Projects'
        boolean isFolder = command.contains("folder");
        String name = extractName(command);
        Path path = Paths.get(BASE_DIR, name);
        try {
            if (isFolder) Files.createDirectory(path);
            else Files.createFile(path);
            return (isFolder ? "Folder" : "File") + " '" + name + "' created on Desktop.";
        } catch (FileAlreadyExistsException e) {
            return "A " + (isFolder?"folder":"file") + " named '" + name + "' already exists.";
        } catch (Exception e) {
            return "Error: Permission denied or invalid name.";
        }
    }

    // UC-06: Delete (returns CONFIRM_NEEDED so UI can ask user first)
    public String delete(String command) {
        String name = extractName(command);
        Path path = Paths.get(BASE_DIR, name);
        if (!Files.exists(path))
            return "Could not find '" + name + "' on Desktop.";
        return "CONFIRM_DELETE:" + name; // CommandRouter will handle the confirmation
    }

    public String confirmDelete(String name) {
        try {
            Files.delete(Paths.get(BASE_DIR, name));
            return "'" + name + "' has been deleted.";
        } catch (Exception e) { return "Error: Could not delete '" + name + "'."; }
    }

    // UC-07: Rename
    public String rename(String command) {
        // Parse: 'rename OldName to NewName'
        String[] parts = command.split(" to ");
        String oldName = parts[0].replace("rename","").trim();
        String newName = parts[1].trim();
        try {
            Files.move(Paths.get(BASE_DIR, oldName), Paths.get(BASE_DIR, newName));
            return "Renamed '" + oldName + "' to '" + newName + "'.";
        } catch (Exception e) { return "Error: Could not rename. Check the name."; }
    }
}

