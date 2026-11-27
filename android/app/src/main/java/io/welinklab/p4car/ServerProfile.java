package io.welinklab.p4car;

import java.net.URI;
import java.util.regex.Pattern;

final class ServerProfile {
    static final String CONTROL_PATH = "/p4-intercom/rtc/";
    static final String DEFAULT_CAR = "default";
    private static final Pattern OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");
    private static final Pattern CAR_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    final String ip;
    final boolean directOnly;
    final String carId;
    final String carName;

    ServerProfile(String input, boolean directOnly) {
        this(input, directOnly, DEFAULT_CAR, "");
    }

    ServerProfile(String input, boolean directOnly, String carId, String carName) {
        String address = input.trim();
        if (address.contains("://")) address = fromHttpsUrl(address);
        if (!isIpv4(address)) throw new IllegalArgumentException("请输入服务器的公网 IPv4 地址。");
        this.ip = address;
        this.directOnly = directOnly;
        this.carId = validCarId(carId) ? carId : DEFAULT_CAR;
        this.carName = carName;
    }

    private static String fromHttpsUrl(String input) {
        try {
            URI uri = URI.create(input);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // Report a single validation error for either input format.
        }
        throw new IllegalArgumentException("请输入公网 IPv4 或 HTTPS 遥控地址。");
    }

    private static boolean isIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!OCTET.matcher(part).matches() || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    String origin() { return "https://" + ip; }

    String carsUrl() { return origin() + CONTROL_PATH + "cars"; }

    String signalUrl() {
        return "wss://" + ip + CONTROL_PATH + "browser"
                + (DEFAULT_CAR.equals(carId) ? "" : "?car=" + carId);
    }

    static boolean validCarId(String id) { return CAR_ID.matcher(id).matches(); }

    boolean sameAs(ServerProfile other) {
        return other != null && ip.equals(other.ip) && directOnly == other.directOnly && carId.equals(other.carId);
    }
}
