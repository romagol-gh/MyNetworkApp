package com.network.api.web;

import com.network.domain.BinRange;
import com.network.domain.Participant;
import com.network.repository.BinRangeRepository;
import com.network.repository.ParticipantRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/bin-ranges")
public class BinRangeWebController {

    private final BinRangeRepository    binRangeRepository;
    private final ParticipantRepository participantRepository;

    public BinRangeWebController(BinRangeRepository binRangeRepository,
                                 ParticipantRepository participantRepository) {
        this.binRangeRepository   = binRangeRepository;
        this.participantRepository = participantRepository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("binRanges", binRangeRepository.findAll());
        model.addAttribute("issuers", participantRepository.findByType(Participant.Type.ISSUER));
        return "bin-ranges/list";
    }

    @PostMapping
    public String create(@RequestParam String low, @RequestParam String high,
                         @RequestParam UUID issuerId, RedirectAttributes ra) {
        Participant issuer = participantRepository.findById(issuerId).orElseThrow();
        BinRange range = new BinRange();
        range.setLow(low);
        range.setHigh(high);
        range.setIssuer(issuer);
        binRangeRepository.save(range);
        ra.addFlashAttribute("success", "BIN range added");
        return "redirect:/bin-ranges";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        binRangeRepository.deleteById(id);
        ra.addFlashAttribute("success", "BIN range deleted");
        return "redirect:/bin-ranges";
    }
}
