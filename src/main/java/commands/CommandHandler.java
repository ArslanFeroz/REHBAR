package commands;

/** Common interface for all command handlers — enables polymorphic dispatch in CommandRouter. */
public interface CommandHandler {
    String execute(String input);
}
