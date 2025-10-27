package com.example.TgStarsBot.repository;

import com.example.TgStarsBot.model.TaskLink;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface TaskRepository extends CrudRepository<TaskLink, Long> {
    Optional<TaskLink> findByLink(String link);
}