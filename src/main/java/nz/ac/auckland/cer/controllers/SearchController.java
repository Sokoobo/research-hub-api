package nz.ac.auckland.cer.controllers;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import nz.ac.auckland.cer.model.ListItem;
import nz.ac.auckland.cer.model.Page;
import nz.ac.auckland.cer.sql.SqlParameter;
import nz.ac.auckland.cer.sql.SqlQuery;
import nz.ac.auckland.cer.sql.SqlStatement;
import org.springframework.web.bind.annotation.*;

import javax.persistence.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@RestController
@Api(tags={"Search"}, description="Site wide search")
public class SearchController extends AbstractController {

    @PersistenceContext
    private EntityManager entityManager;

    SearchController() {
        super();
    }

    @CrossOrigin
    @RequestMapping(method = RequestMethod.GET, value = "/search")
    @ApiOperation(value = "search for content items")
    public Page<ListItem> getSearchResults(@RequestParam Integer page,
                                           @RequestParam Integer size,
                                           @RequestParam(required = false) String orderBy,
                                           @RequestParam(required = false) String searchText,
                                           @RequestParam(required = false) String objectType,
                                           @RequestParam(required = false) List<Integer> people,
                                           @RequestParam(required = false) List<Integer> orgUnits,
                                           @RequestParam(required = false) List<Integer> researchPhases,
                                           @RequestParam(required = false) List<Integer> contentItems,
                                           @RequestParam(required = false) List<Integer> roleTypes,
                                           @RequestParam(required = false) List<Integer> contentTypes) {

        String searchTextProcessed = SqlQuery.preProcessSearchText(searchText);
        boolean searchSearchText = !searchTextProcessed.equals("");

        boolean orderByRelevance = true;
        if(orderBy != null) {
            orderByRelevance = orderBy.equals("relevance");
        }

        List<String> objectTypes = Arrays.asList("content", "person", "policy");

        boolean searchAllTypes = true;
        boolean restrictToContent = false;
        boolean restrictToPerson = false;
        boolean restrictToPolicy = false;

        if (objectType != null && objectTypes.contains(objectType)) {
            restrictToContent = objectType.equals("content");
            restrictToPerson = objectType.equals("person");
            restrictToPolicy = objectType.equals("policy");
            searchAllTypes = false;
        }

        boolean excludePeople = (people != null && people.size() > 0) || (researchPhases != null && researchPhases.size() > 0);
        boolean excludePolicies = (people != null && people.size() > 0) || (researchPhases != null && researchPhases.size() > 0) || (orgUnits != null && orgUnits.size() > 0);

        ArrayList<SqlStatement> statements = new ArrayList<>();

        SqlStatement countStatement = new SqlStatement("SELECT DISTINCT COUNT(*) FROM (", false);
        SqlStatement selectStatement = new SqlStatement("SELECT DISTINCT * FROM (", true);
        statements.add(countStatement);
        statements.add(selectStatement);

        List<Boolean> searchConditions = new ArrayList<>();

        if (searchAllTypes || restrictToContent) {
            statements.add(new SqlStatement(AbstractSearchController.getSelectStatement(searchSearchText, ContentController.SELECT_SQL, ContentController.MATCH_SQL), true));
            statements.addAll(ContentController.getSearchStatements(searchText, contentTypes, researchPhases, people, roleTypes, orgUnits));
            searchConditions.add(true);
        }

        if ((searchAllTypes|| restrictToPerson) && !excludePeople) {
            statements.add(new SqlStatement("UNION", searchConditions.contains(true)));
            statements.add(new SqlStatement(AbstractSearchController.getSelectStatement(searchSearchText, PersonController.SELECT_SQL, PersonController.MATCH_SQL), true));
            statements.addAll(PersonController.getSearchStatements(searchText, orgUnits, contentItems, roleTypes));
            searchConditions.add(true);
        }

        if ((searchAllTypes|| restrictToPolicy) && !excludePolicies) {
            statements.add(new SqlStatement("UNION", searchConditions.contains(true)));
            statements.add(new SqlStatement(AbstractSearchController.getSelectStatement(searchSearchText, PolicyController.SELECT_SQL, PolicyController.MATCH_SQL), true));
            statements.addAll(PolicyController.getSearchStatements(searchText));
        }

        statements.add(new SqlStatement(") AS sitewide", true));

        statements.add(new SqlStatement("ORDER BY relevance DESC", searchSearchText && orderByRelevance));
        statements.add(new SqlStatement("ORDER BY title ASC", !searchSearchText || !orderByRelevance));

        SqlStatement paginationStatement = new SqlStatement("LIMIT :limit OFFSET :offset",
                true,
                new SqlParameter<>("limit", size),
                new SqlParameter<>("offset", page * size));

        // Create native queries and set parameters
        Query contentPaginatedQuery = SqlQuery.generate(entityManager, statements, null, "ListItem");

        countStatement.setExecute(true);
        selectStatement.setExecute(false);
        paginationStatement.setExecute(false);
        Query contentCountQuery = SqlQuery.generate(entityManager, statements, null, null);

        // Get data and return results
        List<ListItem> paginatedResults = contentPaginatedQuery.getResultList();
        int totalElements = ((BigInteger)contentCountQuery.getSingleResult()).intValue();
        return new Page<>(paginatedResults, totalElements, orderBy, size, page);
    }

}