import me.kavishdevar.librepods.data.updates.PersonalUpdatePolicy;

/** Standalone updater contract checks; Android and network access are not required. */
public final class PersonalUpdatePolicyChecks {
    private static int checks;
    private static final String DIGEST = "sha256:" + "ab".repeat(32);

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        expect(Long.valueOf(67).equals(PersonalUpdatePolicy.versionCode("my-v67")),
                "A valid personal tag must expose its version code");
        expect(PersonalUpdatePolicy.versionCode("my-v0") == null, "Version zero is invalid");
        expect(PersonalUpdatePolicy.versionCode("my-v067") == null, "Leading zeroes are invalid");
        expect(PersonalUpdatePolicy.versionCode("v67") == null, "Non-personal tags are invalid");
        expect(PersonalUpdatePolicy.versionCode("my-v67-rc1") == null,
                "Prerelease suffixes are invalid");
        expect(PersonalUpdatePolicy.versionCode("my-v9223372036854775808") == null,
                "Overflowing version codes are invalid");
        expect(PersonalUpdatePolicy.isStrictlyNewer("my-v67", 66),
                "A greater version code is newer");
        expect(!PersonalUpdatePolicy.isStrictlyNewer("my-v66", 66),
                "An equal version code is not newer");
        expect(!PersonalUpdatePolicy.isStrictlyNewer("my-v65", 66),
                "An older version code is not newer");
        expect(!PersonalUpdatePolicy.isStrictlyNewer("v99", 66),
                "An invalid tag cannot be newer");

        expect("ab".repeat(32).equals(PersonalUpdatePolicy.digestHex(DIGEST)),
                "A SHA-256 digest must be parsed");
        expect(PersonalUpdatePolicy.digestHex("sha256:" + "ab".repeat(31)) == null,
                "Short digests are invalid");
        expect(PersonalUpdatePolicy.digestHex("sha512:" + "ab".repeat(32)) == null,
                "Only SHA-256 is accepted");
        expect(PersonalUpdatePolicy.digestHex("sha256:" + "zz".repeat(32)) == null,
                "Non-hex digests are invalid");

        String tag = "my-v67";
        String expected = PersonalUpdatePolicy.expectedAssetUrl(tag);
        expect(PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk", expected, 1),
                "The exact asset contract must be accepted");
        expect(PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk", expected,
                        PersonalUpdatePolicy.APK_LIMIT_BYTES),
                "The maximum APK size is accepted");
        expect(!PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods.apk", expected, 1),
                "A renamed asset is invalid");
        expect(!PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk", expected, 0),
                "An empty APK is invalid");
        expect(!PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk", expected,
                        PersonalUpdatePolicy.APK_LIMIT_BYTES + 1),
                "An oversized APK is invalid");
        expect(!PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk",
                        expected.replace("my-v67", "my-v68"), 1),
                "The asset URL tag must match the release tag");
        expect(!PersonalUpdatePolicy.isExpectedAsset(tag, "LibrePods-My.apk",
                        expected.replace("ssongjay", "attacker"), 1),
                "The asset URL repository must match");

        expect(PersonalUpdatePolicy.isAllowedAssetUrl(expected, tag),
                "The exact GitHub download URL is allowed");
        expect(PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://release-assets.githubusercontent.com/github-production-release-asset/123/file?token=x",
                        tag),
                "GitHub's release asset host is allowed");
        expect(PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://objects.githubusercontent.com/github-production-release-asset/file?token=x",
                        tag),
                "GitHub's objects host is allowed");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl("http://release-assets.githubusercontent.com/file", tag),
                "Plain HTTP redirects are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://release-assets.githubusercontent.com.evil.example/file", tag),
                "Host suffix tricks are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://user@release-assets.githubusercontent.com/file", tag),
                "Credential-bearing URLs are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://release-assets.githubusercontent.com:444/file", tag),
                "Unexpected HTTPS ports are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(expected + "#fragment", tag),
                "URL fragments are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://release-assets.githubusercontent.com/bad path", tag),
                "URLs containing spaces are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://release-assets.githubusercontent.com/%zz", tag),
                "URLs containing malformed percent escapes are invalid");
        expect(!PersonalUpdatePolicy.isAllowedAssetUrl(
                        "https://[invalid]/file", tag),
                "URLs containing malformed brackets are invalid");

        System.out.println("PASS: " + checks + " personal updater policy checks");
    }
}
