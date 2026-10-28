package com.world.cloudxsolution;

/** UI-thread state: transport availability is independent of user input or preferences. */
final class InputSessionState {
    private boolean legacy, modern, control;
    private boolean preferModern = true;

    void preferModern(boolean preferred) { preferModern = preferred; }

    void channel(String label, String state) {
        boolean open = "OPEN".equalsIgnoreCase(state);
        if ("input".equalsIgnoreCase(label)) legacy = open;
        if ("unreliableinput".equalsIgnoreCase(label)) modern = open;
        if ("control".equalsIgnoreCase(label)) control = open;
    }

    String activeChannel() {
        if (modern && (preferModern || !legacy)) return "unreliableinput";
        return legacy ? "input" : "";
    }

    boolean canAnnounce() { return control && !activeChannel().isEmpty(); }
    void reset() { legacy = modern = control = false; }
}
