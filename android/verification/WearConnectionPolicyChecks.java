import me.kavishdevar.librepods.bluetooth.WearConnectionPolicy;

/** Standalone regression checks; only the compiled policy and Kotlin standard library are needed. */
public final class WearConnectionPolicyChecks {
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        WearConnectionPolicy policy = new WearConnectionPolicy();
        expect(policy.observe(true, false, false, 0) == null, "Opening the case must not connect");
        Long token = policy.observe(true, true, false, 100);
        expect(token != null, "First observation already worn must connect");
        expect(policy.observe(true, true, false, 600) == null, "Duplicate broadcasts cannot overlap attempts");
        policy.finish(token, 1000, false);
        expect(policy.observe(true, true, false, 2999) == null, "Failed attempts must back off");
        token = policy.observe(true, true, false, 3000);
        expect(token != null, "A failed attempt must retry");
        policy.finish(token, 3500, false);
        token = policy.observe(true, true, false, 5500);
        expect(token != null, "A third attempt is allowed");
        policy.finish(token, 6000, false);
        expect(policy.observe(true, true, false, 20000) == null, "Retries must stop after three failures");
        policy.observe(true, false, false, 21000);
        token = policy.observe(true, true, false, 22000);
        expect(token != null, "Removing and reinserting must start a new session");
        policy.finish(token, 22500, true);
        expect(policy.observe(true, true, true, 23000) == null, "A connected device needs no request");
        expect(policy.observe(true, true, false, 30000) == null,
                "Manual switching away while still worn must not trigger repeated takeovers");

        WearConnectionPolicy removed = new WearConnectionPolicy();
        Long old = removed.observe(true, true, false, 0);
        removed.observe(true, false, false, 100);
        expect(!removed.isCurrent(old), "Removing earbuds must invalidate a pending request");
        expect(removed.observe(true, true, false, 200) == null,
                "Reinserting during an old request must not overlap it");
        removed.finish(old, 300, true);
        Long next = removed.observe(true, true, false, 400);
        expect(next != null && !next.equals(old), "An old completion cannot mark a new wear session successful");
        removed.observe(false, true, false, 500);
        expect(!removed.isCurrent(next), "Disabling the option must invalidate pending requests");
        removed.finish(next, 600, false);
        expect(removed.observe(false, true, false, 900) == null, "The disabled option must not connect");
        expect(removed.observe(true, true, false, 1000) != null, "Enabling while worn must connect");

        WearConnectionPolicy alreadyConnected = new WearConnectionPolicy();
        expect(alreadyConnected.observe(true, true, true, 0) == null, "Already connected at startup must not reconnect");
        expect(alreadyConnected.observe(true, true, false, 1000) == null,
                "An already connected wear session must respect later manual switching");
        System.out.println("PASS: " + checks + " wear connection regression checks");
    }
}
