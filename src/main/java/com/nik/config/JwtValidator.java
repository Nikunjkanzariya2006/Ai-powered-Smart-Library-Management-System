package com.nik.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;

public class JwtValidator extends OncePerRequestFilter {
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return PATH_MATCHER.match("/auth/**", path)
                || PATH_MATCHER.match("/oauth2/**", path)
                || PATH_MATCHER.match("/login/**", path)
                || "/".equals(path)
                || PATH_MATCHER.match("/test/**", path)
                || PATH_MATCHER.match("/v3/api-docs/**", path)
                || PATH_MATCHER.match("/swagger-ui/**", path);
    }

	@Override
	protected void doFilterInternal(HttpServletRequest request, 
	HttpServletResponse response,
	FilterChain filterChain) throws ServletException, IOException {
		String jwt = request.getHeader(JwtConstant.JWT_HEADER);
		if(jwt!=null && jwt.startsWith("Bearer ")){
			jwt=jwt.substring(7);
			try{
				SecretKey key = Keys.hmacShaKeyFor(JwtConstant.SECRET_KEY.getBytes());
				Claims claims = Jwts.parser().verifyWith(key).build()
						.parseSignedClaims(jwt).getPayload();

				String email = String.valueOf(claims.get("email"));
				String authorities = String.valueOf(claims.get("authorities"));

				List<GrantedAuthority> auths = AuthorityUtils.commaSeparatedStringToAuthorityList(authorities);
				Authentication authentication = new UsernamePasswordAuthenticationToken(email, null, auths);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}catch (Exception e){
				SecurityContextHolder.clearContext();
				response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
				response.setContentType("application/json");
				response.getWriter().write("{\"message\":\"Invalid token\"}");
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

}

