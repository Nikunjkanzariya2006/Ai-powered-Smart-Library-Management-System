package com.nik.service.impl;

import com.nik.domain.UserRole;
import com.nik.model.User;
import com.nik.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;

@Service
public class CustomUserImplementation implements UserDetailsService {

    @Autowired
    private UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        if (username == null || username.trim().isEmpty()) {
            throw new UsernameNotFoundException("email is required");
        }
        String normalizedEmail = username.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (user == null) {
            throw new UsernameNotFoundException("user doesn't exist with email " + normalizedEmail);
        }


        UserRole role = user.getRole() != null ? user.getRole() : UserRole.ROLE_USER;
        GrantedAuthority authority = new SimpleGrantedAuthority(role.toString());
        Collection<? extends GrantedAuthority> authorities = Collections.singletonList(authority);

        return new org.springframework.security.core.userdetails.User(user.getEmail(), user.getPassword(), authorities);
    }

}

