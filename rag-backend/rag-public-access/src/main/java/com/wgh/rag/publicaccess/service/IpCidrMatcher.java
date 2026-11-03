package com.wgh.rag.publicaccess.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IP / CIDR 匹配工具（同时支持 IPv4 与 IPv6）。
 */
public final class IpCidrMatcher {

    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+$");

    private final byte[] network;
    private final int prefixBits;
    private final String spec;

    private IpCidrMatcher(byte[] network, int prefixBits, String spec) {
        this.network = network;
        this.prefixBits = prefixBits;
        this.spec = spec;
    }

    /**
     * 解析 CIDR 或单个 IP；单个 IP 视为 /32（IPv4）或 /128（IPv6）。
     */
    public static IpCidrMatcher parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("IP 白名单条目不能为空");
        }
        String trimmed = value.trim();
        String addressPart = trimmed;
        int prefix = -1;
        int slash = trimmed.indexOf('/');
        if (slash > -1) {
            addressPart = trimmed.substring(0, slash).trim();
            String prefixPart = trimmed.substring(slash + 1).trim();
            try {
                prefix = Integer.parseInt(prefixPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法 CIDR 前缀: " + trimmed);
            }
        }
        InetAddress address = parseLiteral(addressPart);
        byte[] bytes = address.getAddress();
        int bits = bytes.length * 8;
        int effectivePrefix = prefix < 0 ? bits : prefix;
        if (effectivePrefix < 0 || effectivePrefix > bits) {
            throw new IllegalArgumentException("CIDR 前缀超出范围: " + trimmed);
        }
        byte[] network = new byte[bytes.length];
        System.arraycopy(bytes, 0, network, 0, bytes.length);
        mask(network, effectivePrefix);
        String normalized = address.getHostAddress() + "/" + effectivePrefix;
        return new IpCidrMatcher(network, effectivePrefix, normalized);
    }

    /**
     * 解析逗号 / 分号 / 空白分隔的列表；空字符串返回空列表。
     */
    public static List<IpCidrMatcher> parseList(String raw) {
        List<IpCidrMatcher> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                result.add(parse(part));
            }
        }
        return result;
    }

    /**
     * 将列表规范化为逗号分隔字符串；空值返回 null。
     */
    public static String normalizeList(String raw) {
        List<IpCidrMatcher> list = parseList(raw);
        if (list.isEmpty()) {
            return null;
        }
        List<String> specs = new ArrayList<>();
        for (IpCidrMatcher matcher : list) {
            specs.add(matcher.spec);
        }
        return String.join(",", specs);
    }

    public boolean matches(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        InetAddress address;
        try {
            address = parseLiteral(canonical(ip));
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length != network.length) {
            return false;
        }
        byte[] masked = new byte[bytes.length];
        System.arraycopy(bytes, 0, masked, 0, bytes.length);
        mask(masked, prefixBits);
        for (int i = 0; i < masked.length; i++) {
            if (masked[i] != network[i]) {
                return false;
            }
        }
        return true;
    }

    public String spec() {
        return spec;
    }

    private static InetAddress parseLiteral(String value) {
        String canonical = canonical(value);
        if (!IPV4.matcher(canonical).matches() && !IPV6.matcher(canonical).matches()) {
            throw new IllegalArgumentException("非法 IP 地址: " + value);
        }
        try {
            return InetAddress.getByName(canonical);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("非法 IP 地址: " + value, e);
        }
    }

    static String canonical(String value) {
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("::ffff:") && IPV4.matcher(lower.substring(7)).matches()) {
            return lower.substring(7);
        }
        return v;
    }

    private static void mask(byte[] bytes, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        int zeroFrom = remainingBits == 0 ? fullBytes : fullBytes + 1;
        for (int i = zeroFrom; i < bytes.length; i++) {
            bytes[i] = 0;
        }
        if (remainingBits > 0 && fullBytes < bytes.length) {
            int mask = 0xFF << (8 - remainingBits);
            bytes[fullBytes] = (byte) (bytes[fullBytes] & mask);
        }
    }
}
