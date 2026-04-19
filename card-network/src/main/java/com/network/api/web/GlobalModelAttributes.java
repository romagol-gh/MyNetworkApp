package com.network.api.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {
        DashboardController.class,
        ParticipantWebController.class,
        BinRangeWebController.class,
        TransactionWebController.class,
        ClearingWebController.class,
        FraudWebController.class,
        LoginController.class,
        TestLabWebController.class,
        FeesWebController.class,
        DisputeWebController.class,
        AgentWebController.class
})
public class GlobalModelAttributes {

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
