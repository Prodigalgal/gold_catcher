package com.goldcatcher.auth;

import java.util.List;

/** Stable grants shared by PAT, OAuth, UI, REST and MCP. Old grants never gain new scopes. */
public final class AccessScopes {
    private AccessScopes() {}
    public static final List<String> DEFAULT = List.of("market:read", "information:read", "research:read");
    public static final List<String> ALL = List.of("market:read", "information:read", "research:read", "simulation:read",
        "configuration:read", "system:read", "simulation:write", "research:write", "information:write", "configuration:write", "access:manage");
    public static boolean writes(String scope) { return scope.endsWith(":write") || scope.equals("access:manage"); }
}
