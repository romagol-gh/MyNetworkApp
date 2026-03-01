package com.network.api.web;

import com.network.domain.Transaction;
import com.network.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Controller
@RequestMapping("/transactions")
public class TransactionWebController {

    private final TransactionRepository repository;

    public TransactionWebController(TransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(Model model,
                       @RequestParam(defaultValue = "0") int page,
                       @RequestParam(defaultValue = "50") int size) {
        Page<Transaction> transactions = repository.findAll(
                PageRequest.of(page, Math.min(size, 200), Sort.by("transmittedAt").descending()));
        model.addAttribute("transactions", transactions);
        return "transactions/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        Transaction txn = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("transaction", txn);
        return "transactions/detail";
    }
}
