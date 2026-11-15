package ae.afia.core.patient;

import ae.afia.core.platform.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patients;

    public PatientController(PatientService patients) {
        this.patients = patients;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PatientService.Patient create(@RequestBody PatientService.NewPatient body) {
        return patients.create(CurrentUser.current(), body);
    }

    @GetMapping
    List<PatientService.Patient> search(@RequestParam(required = false) String query) {
        return patients.search(CurrentUser.current(), query);
    }
}
