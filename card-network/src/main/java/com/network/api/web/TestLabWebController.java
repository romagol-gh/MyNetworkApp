package com.network.api.web;

import com.network.transaction.TestLabService;
import com.network.transaction.TestScenarioType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/test-lab")
public class TestLabWebController {

    private final TestLabService testLabService;

    public TestLabWebController(TestLabService testLabService) {
        this.testLabService = testLabService;
    }

    @GetMapping
    public String testLab(Model model) {
        model.addAttribute("scenarios", TestScenarioType.values());
        model.addAttribute("recentRuns", testLabService.recentRuns()
                .stream().limit(10).toList());
        return "test-lab";
    }
}
