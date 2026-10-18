package dev.resilient.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader("X-Request-Id");
    String id =
        incoming != null && incoming.matches("[A-Za-z0-9._-]{1,80}")
            ? incoming
            : UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", id);
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove("requestId");
    }
  }
}
