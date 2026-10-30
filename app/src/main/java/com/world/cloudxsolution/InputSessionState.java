package com.world.cloudxsolution;

/** UI-thread state: transport availability is independent of user input or preferences. */
final class InputSessionState {
    private boolean legacy, modern, control, authorized;
    private boolean preferModern = true;

    void preferModern(boolean preferred) { preferModern = preferred; }

    void channel(String label, String state) {
        boolean open = "OPEN".equalsIgnoreCase(state);
        if ("input".equalsIgnoreCase(label)) legacy = open;
        if ("unreliableinput".equalsIgnoreCase(label)) modern = open;
        if ("control".equalsIgnoreCase(label)) { control = open; if (!open) authorized = false; }
    }

    void snapshot(boolean legacy, boolean modern, boolean control) {
        this.legacy = legacy;
        this.modern = modern;
        this.control = control;
        if (!control) authorized = false;
    }

    String activeChannel() {
        if (modern && (preferModern || !legacy)) return "unreliableinput";
        return legacy ? "input" : "";
    }

    void authorizationSent() { authorized = true; }
    boolean authorized() { return authorized; }
    boolean canAnnounce() { return authorized && control && !activeChannel().isEmpty(); }
    void reset() { legacy = modern = control = authorized = false; }
}
