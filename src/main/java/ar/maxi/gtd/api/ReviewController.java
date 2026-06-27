package ar.maxi.gtd.api;

import ar.maxi.gtd.service.VaultService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final VaultService vault;

    public ReviewController(VaultService vault) {
        this.vault = vault;
    }

    @GetMapping("/review")
    public Map<String, Object> review(
            @RequestParam(defaultValue = "3") int staleDays,
            @RequestParam(defaultValue = "7") int dueDays,
            @RequestParam(defaultValue = "7") int completedDays) {

        List<Map<String, Object>> staleToday       = vault.listStaleToday(staleDays);
        List<Map<String, Object>> dueSoon          = vault.listDueSoon(dueDays);
        List<Map<String, Object>> completedThisWeek = vault.listCompletedSince(completedDays);

        Map<String, Object> weekStats = new LinkedHashMap<>();
        weekStats.put("stale", staleToday.size());
        weekStats.put("completed", completedThisWeek.size());
        weekStats.put("due_soon", dueSoon.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stale_today", staleToday);
        result.put("due_this_week", dueSoon);
        result.put("completed_this_week", completedThisWeek);
        result.put("week_stats", weekStats);
        return result;
    }
}
