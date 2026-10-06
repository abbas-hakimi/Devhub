package com.versioncontrol.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.versioncontrol.entity.User;
import com.versioncontrol.repository.UserRepository;

@Service
public class VersionControlUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public VersionControlUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameIgnoreCase(identifier)
                .or(() -> userRepository.findByEmailIgnoreCase(identifier))
                .orElseThrow(() -> new UsernameNotFoundException("User not found."));
        return UserPrincipal.from(user);
    }

    public UserPrincipal loadUserById(Long id) throws UsernameNotFoundException {
        return userRepository.findById(id)
                .map(UserPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found."));
    }
}

