package com.atlas.identity;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a login came from. The IP is the socket peer address; behind a load
 * balancer, enable server.forward-headers-strategy so it reflects the real
 * client (Phase 9 deployment).
 */
record ClientInfo(String ip, String userAgent) {

    static ClientInfo from(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        if (agent != null && agent.length() > 300) {
            agent = agent.substring(0, 300);
        }
        return new ClientInfo(request.getRemoteAddr(), agent);
    }
}
