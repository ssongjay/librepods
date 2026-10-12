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
        expect(token != null, "An observed out-of-ear to in-ear transition must connect");
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
        removed.observe(true, false, false, 0);
        Long old = removed.observe(true, true, false, 10);
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
        expect(removed.observe(true, true, false, 1000) == null, "Enabling while already worn must not steal audio");
        removed.observe(true, false, false, 1100);
        expect(removed.observe(true, true, false, 1200) != null, "Reinserting after enabling must connect");

        WearConnectionPolicy alreadyConnected = new WearConnectionPolicy();
        expect(alreadyConnected.observe(true, true, true, 0) == null, "Already connected at startup must not reconnect");
        expect(alreadyConnected.observe(true, true, false, 1000) == null,
                "An already connected wear session must respect later manual switching");

        WearConnectionPolicy startup = new WearConnectionPolicy();
        expect(startup.observe(true, true, false, 0) == null,
                "Starting the app while worn on a Mac must not connect to the phone");
        expect(startup.observe(true, true, false, 1000) == null,
                "Repeated already-worn packets must not become an insertion event");
        startup.observe(true, false, false, 2000);
        expect(startup.observe(true, true, false, 2500) != null,
                "Real insertion after startup must still connect");

        WearConnectionPolicy gap = new WearConnectionPolicy();
        gap.observe(true, false, false, 0);
        Long beforeGap = gap.observe(true, true, false, 100);
        gap.finish(beforeGap, 500, true);
        gap.forgetObservation();
        expect(gap.observe(true, true, false, 21000) == null,
                "Rediscovery after a BLE gap must not steal a Mac connection");
        expect(gap.observe(true, true, false, 22000) == null,
                "A BLE gap must not restart the retry budget while still worn");
        gap.observe(true, false, false, 23000);
        expect(gap.observe(true, true, false, 24000) != null,
                "A real new insertion must work after a BLE gap");

        WearConnectionPolicy activeMac = new WearConnectionPolicy();
        activeMac.observe(true, false, false, 0);
        expect(activeMac.observe(true, true, false, 100, true) == null,
                "Mac playback or a call must take priority over wear auto-connect");
        expect(activeMac.observe(true, true, false, 1000, false) == null,
                "Pausing Mac playback must not trigger a delayed phone takeover");
        activeMac.observe(true, false, false, 2000);
        Long newInsertion = activeMac.observe(true, true, false, 2500, false);
        expect(newInsertion != null, "An idle remote device permits a real new insertion");
        expect(activeMac.observe(true, true, false, 2600, true) == null,
                "Remote playback starting during an attempt must not start another request");
        expect(!activeMac.isCurrent(newInsertion), "Remote playback must invalidate queued audio requests");
        activeMac.finish(newInsertion, 2700, false);
        expect(activeMac.observe(true, true, false, 5000, false) == null,
                "An interrupted attempt must not retry after remote playback stops");

        WearConnectionPolicy lostDuringAttempt = new WearConnectionPolicy();
        lostDuringAttempt.observe(true, false, false, 0);
        Long pending = lostDuringAttempt.observe(true, true, false, 100);
        lostDuringAttempt.forgetObservation();
        expect(!lostDuringAttempt.isCurrent(pending), "BLE disappearance must invalidate pending work");
        expect(lostDuringAttempt.observe(true, true, false, 20000) == null,
                "Rediscovery cannot reuse a pending wear session");
        lostDuringAttempt.finish(pending, 21000, false);
        expect(lostDuringAttempt.observe(true, true, false, 22000) == null,
                "Completion of old work cannot arm a rediscovered already-worn device");
        System.out.println("PASS: " + checks + " wear connection regression checks");
    }
}
