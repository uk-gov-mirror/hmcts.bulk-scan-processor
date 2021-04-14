package uk.gov.hmcts.reform.bulkscanprocessor.controllers;

import javassist.tools.web.BadHttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.reform.bulkscanprocessor.model.out.zipfilestatus.ZipFileStatus;
import uk.gov.hmcts.reform.bulkscanprocessor.services.zipfilestatus.ZipFileStatusService;

import javax.management.BadAttributeValueExpException;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(
    path = "/zip-files",
    produces = MediaType.APPLICATION_JSON_VALUE
)
public class ZipStatusController {

    private final ZipFileStatusService service;

    // region constructor
    public ZipStatusController(ZipFileStatusService service) {
        this.service = service;
    }
    // endregion

    @RequestMapping
    public ResponseEntity<List<ZipFileStatus>> findByFileNameOrDCN(@RequestParam Map<String,String> params){
        String fileName = params.get("name");
        String dcn = params.get("dcn");
        if (fileName != null && dcn == null) {
            List<ZipFileStatus> zipFileStatuses = new ArrayList<>();
            zipFileStatuses.add(service.getStatusFor(fileName));
            return ResponseEntity.ok().body(zipFileStatuses);
        }
        else if (dcn!= null && fileName == null) {
            return ResponseEntity.ok().body(service.getStatusByDcn(dcn));
        }
        return ResponseEntity.badRequest().body(null);
    }
}
