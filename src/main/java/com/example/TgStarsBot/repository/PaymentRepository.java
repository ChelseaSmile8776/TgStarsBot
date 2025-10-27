package com.example.TgStarsBot.repository;

import com.example.TgStarsBot.model.Payment;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface PaymentRepository extends CrudRepository<Payment, Long> {
    List<Payment> findByChatIdOrderByCreatedAtDesc(Long chatId);
}
