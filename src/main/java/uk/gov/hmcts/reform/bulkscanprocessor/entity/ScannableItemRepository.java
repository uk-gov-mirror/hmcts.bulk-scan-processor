package uk.gov.hmcts.reform.bulkscanprocessor.entity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ScannableItemRepository extends JpaRepository<ScannableItem, UUID> {

    /**
     * Finds envelope with a blob not deleted for a given container and zip file name.
     *
     * @param documentControllNumber of scannable item.
     * @return A list of envelopes.
     */
    @Query("select item from ScannableItem item"
        + " where item.documentControlNumber like :documentControllNumber"
        + " order by item.scanningDate desc"
    )
    List<ScannableItem> findByDcn(
        @Param("documentControllNumber") String documentControllNumber
    );

}
