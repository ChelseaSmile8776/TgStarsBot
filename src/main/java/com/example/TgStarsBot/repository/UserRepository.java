package com.example.TgStarsBot.repository;

import com.example.TgStarsBot.model.User;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface UserRepository extends CrudRepository<User, Long> {
    Optional<User> findByRefCode(String refCode);
    int countByReferredBy(String refCode);

}
