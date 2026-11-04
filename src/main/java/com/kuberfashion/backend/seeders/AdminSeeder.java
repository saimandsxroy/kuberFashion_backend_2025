package com.kuberfashion.backend.seeders;

import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// @Component - Disabled to prevent duplicate user conflict; DataInitializer manages admin@kuberfashion.in
public class AdminSeeder implements CommandLineRunner {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        if (!userRepository.existsByEmail("admin@kuberfashion.com") && !userRepository.existsByPhone("1234567890")) {
            User admin = new User();
            admin.setFirstName("Admin");
            admin.setLastName("User");
            admin.setEmail("admin@kuberfashion.com");
            admin.setPassword(passwordEncoder.encode("admin123456"));
            admin.setPhone("1234567890");
            admin.setRole(User.Role.ADMIN);
            admin.setEnabled(true);

            userRepository.save(admin);
            System.out.println("✅ Admin user seeded successfully!");
        } else {
            System.out.println("ℹ️ Admin user already exists.");
        }
    }
}
