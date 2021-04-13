package uk.gov.hmcts.reform.bulkscanprocessor.controllers;

import javassist.tools.web.BadHttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.hmcts.reform.bulkscanprocessor.model.out.zipfilestatus.ZipFileStatus;
import uk.gov.hmcts.reform.bulkscanprocessor.services.zipfilestatus.ZipFileStatusService;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
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
    public List<ZipFileStatus> findByFileNameOrDCN(@RequestParam(required = true) @NotNull @Size(max = 1) Map<String,String> params) throws HttpClientErrorException.BadRequest {

        String fileName = params.get("fileName");
        String dcn = params.get("dcn");

        if (fileName != null) {
            List<ZipFileStatus> zipFileStatuses = new ArrayList<>();
            zipFileStatuses.add(service.getStatusFor(fileName));
            return zipFileStatuses;
        }
        else if (dcn!= null) {
            return service.getStatusByDcn(dcn);
        }
        return null;
    }

}
