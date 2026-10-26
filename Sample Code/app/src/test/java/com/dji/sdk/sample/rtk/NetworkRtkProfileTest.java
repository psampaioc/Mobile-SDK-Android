package com.dji.sdk.sample.rtk;

/** Dependency-free profile contract; Android Keystore persistence is device-tested. */
public final class NetworkRtkProfileTest {
    public static void main(String[] args) {
        validProfile(); invalidProfile();
        System.out.println("Network RTK profile tests passed");
    }
    private static void validProfile() {
        NetworkRtkProfile profile = new NetworkRtkProfile(" caster.example ", 2101, "user", "secret", " MOUNT ");
        require(profile.isValid(), "valid NTRIP profile");
        require("caster.example".equals(profile.host) && "MOUNT".equals(profile.mountpoint), "fields trim");
    }
    private static void invalidProfile() {
        require(!new NetworkRtkProfile("", 2101, "", "", "M").isValid(), "host required");
        require(!new NetworkRtkProfile("x", 0, "", "", "M").isValid(), "port lower bound");
        require(!new NetworkRtkProfile("x", 65536, "", "", "M").isValid(), "port upper bound");
        require(!new NetworkRtkProfile("x", 2101, "", "", "").isValid(), "mountpoint required");
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
