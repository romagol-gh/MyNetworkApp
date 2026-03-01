package com.network.api.web;

import com.network.domain.Participant;
import com.network.repository.ParticipantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/participants")
public class ParticipantWebController {

    private final ParticipantRepository repository;

    public ParticipantWebController(ParticipantRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("participants", repository.findAll());
        return "participants/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("participant", new Participant());
        model.addAttribute("types", Participant.Type.values());
        return "participants/form";
    }

    @PostMapping
    public String create(@ModelAttribute Participant participant, RedirectAttributes ra) {
        repository.save(participant);
        ra.addFlashAttribute("success", "Participant created successfully");
        return "redirect:/participants";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("participant", repository.findById(id).orElseThrow());
        model.addAttribute("types", Participant.Type.values());
        model.addAttribute("statuses", Participant.Status.values());
        return "participants/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id, @ModelAttribute Participant form, RedirectAttributes ra) {
        Participant existing = repository.findById(id).orElseThrow();
        existing.setName(form.getName());
        existing.setHost(form.getHost());
        existing.setPort(form.getPort());
        existing.setStatus(form.getStatus());
        repository.save(existing);
        ra.addFlashAttribute("success", "Participant updated");
        return "redirect:/participants";
    }
}
