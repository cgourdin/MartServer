/**
 * Copyright (c) 2015-2017 Inria
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p>
 * Contributors:
 * - Christophe Gourdin <christophe.gourdin@inria.fr>
 */
package org.occiware.mart.server.servlet.impl.parser.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EEnum;
import org.occiware.clouddesigner.occi.*;
import org.occiware.mart.server.servlet.exception.AttributeParseException;
import org.occiware.mart.server.servlet.exception.CategoryParseException;
import org.occiware.mart.server.servlet.exception.ResponseParseException;
import org.occiware.mart.server.servlet.facade.AbstractRequestParser;
import org.occiware.mart.server.servlet.impl.parser.json.render.*;
import org.occiware.mart.server.servlet.impl.parser.json.render.queryinterface.*;
import org.occiware.mart.server.servlet.impl.parser.json.utils.InputData;
import org.occiware.mart.server.servlet.impl.parser.json.utils.ValidatorUtils;
import org.occiware.mart.server.servlet.model.ConfigurationManager;
import org.occiware.mart.server.servlet.model.exceptions.ConfigurationException;
import org.occiware.mart.server.servlet.utils.Constants;
import org.occiware.mart.server.servlet.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.*;

/**
 * @author cgourdin
 */
public class JsonOcciParser extends AbstractRequestParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonOcciParser.class);

    public static final String EMPTY_JSON = "{ }";

    @Override
    public void parseInputQuery(HttpHeaders headers, HttpServletRequest request) throws CategoryParseException, AttributeParseException {
        // Parse request parameters (for filtering, for pagination or for action.
        parseRequestParameters(request);
        // If we are here, a json file has been uploaded.
        // We dont call the super method because the behavior is not the same. It may here have multiple entity to operate.
        // The json file may contains :
        // A resource (with or without links).
        // A link
        // A mixin 
        // An action invocation.
        // A mixin tag.
        // A collection of resources ==> to create or to update (with or without links).
        parseInputQueryToDatas(request);

    }

    /**
     * Parse the input query in multiple InputData objects.
     *
     * @param request
     * @throws CategoryParseException
     * @throws AttributeParseException
     */
    private void parseInputQueryToDatas(HttpServletRequest request) throws CategoryParseException, AttributeParseException {
        InputStream jsonInput = null;
        LOGGER.info("Parsing input uploaded datas...");
        OcciMainJson occiMain;
        ObjectMapper mapper = new ObjectMapper();
        // Define those attributes have only non null values.
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String contentJson;
        try {
            jsonInput = request.getInputStream();
            if (jsonInput == null) {
                throw new CategoryParseException("The input has no content delivered.");
            }
            contentJson = Utils.convertInputStreamToString(jsonInput);

        } catch (IOException ex) {
            throw new CategoryParseException("The server cant read the json file input --> " + ex.getMessage());
        } finally {
            Utils.closeQuietly(jsonInput);
        }
        boolean isCollectionRes;
        boolean isResourceSingle;
        boolean isLinkSingle = false;
        boolean isMixinTagSingle = false;
        boolean isActionInvocation = false;
        String messages = "";

        if (!contentJson.isEmpty()) {
            // Try on occi main json (for multiple resources/links/mixins).
            try {
                occiMain = mapper.readValue(contentJson, OcciMainJson.class);
                parseMainInput(occiMain);
                isCollectionRes = true;
            } catch (IOException ex) {
                messages += ex.getMessage();
                isCollectionRes = false;
            }

            // for one resource, if this is not a collection (resources,links etc..).
            // it goes to try to get a single resource.
            if (!isCollectionRes) {
                try {
                    ResourceJson resJson = mapper.readValue(contentJson, ResourceJson.class);
                    parseResourceJsonInput(resJson);
                    isResourceSingle = true;
                } catch (IOException ex) {
                    messages += " " + ex.getMessage();
                    isResourceSingle = false;
                }

                if (!isResourceSingle) {
                    try {
                        LinkJson linkJson = mapper.readValue(contentJson, LinkJson.class);
                        parseLinkJsonInput(linkJson);
                        isLinkSingle = true;
                    } catch (IOException ex) {
                        messages += " " + ex.getMessage();
                        isLinkSingle = false;
                    }
                }

                // Try to parse single mixin tag.
                if (!isResourceSingle && !isLinkSingle) {
                    try {
                        MixinJson mixinJson = mapper.readValue(contentJson, MixinJson.class);
                        parseMixinJsonTagInput(mixinJson);
                        isMixinTagSingle = true;
                    } catch (IOException ex) {
                        messages += " " + ex.getMessage();
                        isMixinTagSingle = false;
                    }
                }
                // Try to read action json invocation.
                if (!isResourceSingle && !isLinkSingle && !isMixinTagSingle) {
                    try {
                        ActionJson actionJson = mapper.readValue(contentJson, ActionJson.class);
                        parseActionJsonInvocationInput(actionJson);
                        isActionInvocation = true;
                    } catch (IOException ex) {
                        messages += " " + ex.getMessage();
                        isActionInvocation = false;
                    }
                }

                // If all tries are failed throw an exception with otherall exception messages.
                if (!isResourceSingle && !isLinkSingle && !isMixinTagSingle && !isActionInvocation) {
                    LOGGER.error("Unknown json input file, please check your file input. " + messages);
                    throw new CategoryParseException("Unknown json input file, please check your file. " + messages);
                }
            }
        } else {
            // We parse here only the path.
            List<InputData> datas = getInputDatas();
            InputData data = new InputData();
            datas.add(data);
            this.setInputDatas(datas);
        }

    }

    /**
     * Parse collection input files, may defines resources, links, mixins+tag.
     *
     * @param mainJson
     * @throws CategoryParseException
     * @throws AttributeParseException
     */
    public void parseMainInput(final OcciMainJson mainJson) throws CategoryParseException, AttributeParseException {
        if (mainJson == null) {
            throw new CategoryParseException("unknown json format.");
        }
        List<ResourceJson> resources = mainJson.getResources();
        List<LinkJson> links = mainJson.getLinks();
        List<KindJson> kinds = mainJson.getKinds();
        List<MixinJson> mixins = mainJson.getMixins();
        List<ActionJson> actions = mainJson.getActions();
        // if none objects is set, throw a category parse exception.
        boolean hasResources = resources != null && !resources.isEmpty();
        boolean hasLinks = links != null && !links.isEmpty();
        boolean hasMixins = mixins != null && !mixins.isEmpty();
        boolean hasActions = actions != null && !actions.isEmpty();
        boolean hasKinds = kinds != null && !kinds.isEmpty();

        if (!hasResources
                && !hasLinks && !hasMixins && !hasActions && !hasKinds) {
            throw new CategoryParseException("No content upload parsed. Check your json file input.");
        }
        if (links == null) {
            links = new LinkedList<>();
        }
        if (hasResources) {
            for (ResourceJson resource : resources) {
                parseResourceJsonInput(resource);
            }
        }
        if (hasLinks) {
            for (LinkJson link : links) {
                parseLinkJsonInput(link);
            }
        }

        if (hasMixins) {
            for (MixinJson mixin : mixins) {
                parseMixinJsonTagInput(mixin);
            }
        }

        if (hasKinds) {
            // TODO : Add filter on kinds if the query is a get with upload json file.
        }
        // Multiple Actions invocation.
        if (hasActions) {
            for (ActionJson json : actions) {
                parseActionJsonInvocationInput(json);
            }
        }
    }

    /**
     * Load an input data from resource. If there are links on this resources.
     *
     * @param resource
     * @throws CategoryParseException
     * @throws AttributeParseException
     */
    private void parseResourceJsonInput(ResourceJson resource) throws CategoryParseException, AttributeParseException {
        Map<String, Object> attrs;
        InputData data = new InputData();
        String title = resource.getTitle();
        String summary = resource.getSummary();
        String id = resource.getId();
        String kind = resource.getKind();
        String location = resource.getLocation();
        attrs = resource.getAttributes();
        List<InputData> datas = getInputDatas();
        if (attrs == null) {
            attrs = new HashMap<>();
        }
        // set title, summary and id.
        if (id != null) {
            if (!id.startsWith(Constants.URN_UUID_PREFIX)) {
                attrs.put(Constants.OCCI_CORE_ID, Constants.URN_UUID_PREFIX + id);
            } else {
                attrs.put(Constants.OCCI_CORE_ID, id);
                id = id.replace(Constants.URN_UUID_PREFIX, "");
            }
            data.setEntityUUID(id);
        }
        if (title != null && !title.isEmpty()) {
            attrs.put(Constants.OCCI_CORE_TITLE, title);
        }
        if (summary != null && !summary.isEmpty()) {
            attrs.put(Constants.OCCI_CORE_SUMMARY, summary);
        }
        data.setAttrObjects(attrs);

        if (kind == null) {
            throw new CategoryParseException("Kind is not defined for resource: " + id);
        }
        data.setKind(kind);

        data.setLocation(location);

        List<String> mixinsRes = resource.getMixins();
        if (mixinsRes != null && !mixinsRes.isEmpty()) {
            data.setMixins(mixinsRes);
        }
        List<String> actionRes = resource.getActions();
        if (actionRes != null && !actionRes.isEmpty()) {
            data.setAction(actionRes.get(0));
            // We set the first action.
            // TODO : Define if actions is assigned to a resource if we must execute them...
        }
        datas.add(data);
        // If there are links defined on this resource, we add them to the input datas linked list.
        // We add the links on input data after the resources to be sure they are all exists if we are in create mode or update mode.
        if (resource.getLinks() != null && !resource.getLinks().isEmpty()) {
            List<LinkJson> links = resource.getLinks();
            for (LinkJson link : links) {
                parseLinkJsonInput(link);
            }
        }
        this.setInputDatas(datas);
    }

    /**
     * Parse a single link input and return the corresponding InputData object.
     *
     * @param link
     * @return
     */
    private void parseLinkJsonInput(LinkJson link) throws CategoryParseException, AttributeParseException {
        Map<String, Object> attrs;
        InputData data = new InputData();
        String title = link.getTitle();
        String summary = link.getSummary();
        String id = link.getId();
        String kind = link.getKind();
        String location = link.getLocation();
        SourceJson source = link.getSource();
        TargetJson target = link.getTarget();
        String sourceLocation;
        String targetLocation;
        attrs = link.getAttributes();
        List<InputData> datas = getInputDatas();
        if (attrs == null) {
            attrs = new HashMap<>();
        }
        // set title, summary and id.
        if (id != null) {
            if (!id.startsWith(Constants.URN_UUID_PREFIX)) {
                attrs.put(Constants.OCCI_CORE_ID, Constants.URN_UUID_PREFIX + id);
            } else {
                attrs.put(Constants.OCCI_CORE_ID, id);
                id = id.replace(Constants.URN_UUID_PREFIX, "");
            }
            data.setEntityUUID(id);
        }
        if (title != null && !title.isEmpty()) {
            attrs.put(Constants.OCCI_CORE_TITLE, title);
        }
        if (summary != null && !summary.isEmpty()) {
            attrs.put(Constants.OCCI_CORE_SUMMARY, summary);
        }
        if (source == null) {
            throw new AttributeParseException("No source set for link: " + id + " , check your json query.");
        }
        if (target == null) {
            throw new AttributeParseException("No target set for link: " + id + " , check your json query.");
        }
        sourceLocation = source.getLocation();
        targetLocation = target.getLocation();
        if (sourceLocation == null || sourceLocation.isEmpty()) {
            throw new AttributeParseException("No source location set for link : " + id + " , check your json query.");
        }
        if (targetLocation == null || targetLocation.isEmpty()) {
            throw new AttributeParseException("No target location set for link : " + id + " , check your json query.");
        }
        attrs.put(Constants.OCCI_CORE_TARGET, targetLocation);
        attrs.put(Constants.OCCI_CORE_SOURCE, sourceLocation);
        data.setAttrObjects(attrs);

        if (kind == null) {
            throw new CategoryParseException("Kind is not defined for resource: " + id);
        }
        data.setKind(kind);

        data.setLocation(location);

        List<String> mixinsRes = link.getMixins();
        if (mixinsRes != null && !mixinsRes.isEmpty()) {
            data.setMixins(mixinsRes);
        }

        datas.add(data);
        this.setInputDatas(datas);
    }

    /**
     * Parse mixin tag define in MixinJson object.
     *
     * @param mixinTag
     * @throws CategoryParseException
     * @throws AttributeParseException
     */
    private void parseMixinJsonTagInput(MixinJson mixinTag) throws CategoryParseException, AttributeParseException {
        List<InputData> datas = this.getInputDatas();
        String title = mixinTag.getTitle();
        String term = mixinTag.getTerm();
        String location = mixinTag.getLocation();
        String scheme = mixinTag.getScheme();
        Map<String, Object> attrs = mixinTag.getAttributes();
        if (attrs != null && !attrs.isEmpty()) {
            throw new AttributeParseException("The attributes on mixin tag must be empty.");
        }
        if (location == null || location.trim().isEmpty()) {
            throw new CategoryParseException("The location on mixin tag must be set, like /mytag/my_stuff/");
        }
        if (term == null || term.trim().isEmpty()) {
            throw new CategoryParseException("A term must be set for a mixin tag.");
        }
        if (scheme == null || scheme.trim().isEmpty()) {
            throw new CategoryParseException("A scheme must be set for a mixin tag.");
        }
        // Set the input data object and add it to the global list.
        InputData data = new InputData();
        data.setAttrObjects(attrs);
        data.setMixinTag(scheme + term);
        data.setMixinTagTitle(title);
        data.setLocation(location);
        datas.add(data);
        this.setInputDatas(datas);
    }

    /**
     * Parse action invocation.
     *
     * @param action
     */
    private void parseActionJsonInvocationInput(ActionJson action) {
        List<InputData> datas = this.getInputDatas();
        InputData data = new InputData();
        data.setAction(action.getAction());
        data.setAttrObjects(action.getAttributes());
        datas.add(data);
        this.setInputDatas(datas);
    }

    @Override
    public void parseOcciCategories(HttpHeaders headers, HttpServletRequest request) throws CategoryParseException {
        // This method has no effect here.
        throw new UnsupportedOperationException("Not supported for Json queries.");
    }

    @Override
    public void parseOcciAttributes(HttpHeaders headers, HttpServletRequest request) throws AttributeParseException {
        // This method has no effect here.
        throw new UnsupportedOperationException("Not supported for Json queries.");

    }

    @Override
    public Response parseResponse(Object object, Response.Status status) throws ResponseParseException {
        Response response = null;
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String msg;
        if (status == null) {
            status = Response.Status.OK;
        }
        try {
            // Case 1 : Object is a Response object.
            if (object instanceof Response) {
                MessageJson msgJson = new MessageJson();
                msgJson.setStatus(status.getStatusCode());
                if (status.equals(Response.Status.OK)) {
                    msgJson.setMessage("ok");

                } else {
                    msgJson.setMessage((String) ((Response) object).getEntity());
                }
                response = Response.fromResponse((Response) object)
                        .header("Server", Constants.OCCI_SERVER_HEADER)
                        .header("Accept", getAcceptedTypes())
                        .type(Constants.MEDIA_TYPE_JSON)
                        .entity(msgJson.toStringJson())
                        .status(status)
                        .build();
            }
            // Case 2 : Object is a String.
            if (object instanceof String) {
                String msgContent = (String) object;
                if (msgContent.equals(EMPTY_JSON)) {
                    response = renderResponseWithMesssageContent(status, msgContent);
                } else {
                    MessageJson msgJson = new MessageJson();
                    msgJson.setStatus(status.getStatusCode());
                    if (status.equals(Response.Status.OK)) {
                        msgJson.setMessage("ok");
                    } else {
                        msgJson.setMessage((String) object);
                    }
                    response = renderResponseWithMesssageContent(status, msgJson.toStringJson());
                }
            }

            if (object instanceof Entity) {
                // Build an object response from entity occiware object model.
                Entity entity = (Entity) object;
                List<Entity> entities = new LinkedList<>();
                entities.add(entity);
                response = renderEntitiesResponse(entities, status);
            }

            if (object instanceof List<?>) {
                LOGGER.info("Collection to render.");
                List<?> objects = (List<?>) object;

                List<String> locations = new LinkedList<>();
                List<Entity> entities = new LinkedList<>();
                String tmp;
                Entity entityTmp;
                // To determine if location or if entities to render..
                for (Object objectTmp : objects) {
                    if (objectTmp instanceof String) {
                        // List of locations.
                        tmp = (String) objectTmp;
                        locations.add(tmp);

                    } else if (objectTmp instanceof Entity) {
                        // List of entities to render.
                        entityTmp = (Entity) objectTmp;
                        entities.add(entityTmp);

                    } else {
                        throw new ResponseParseException("unknown datatype collection.");
                    }
                }

                if (!locations.isEmpty()) {
                    // Build a json object output.
                    LocationsJson locationsJson = new LocationsJson();
                    locationsJson.setLocations(locations);
                    msg = locationsJson.toStringJson();
                    response = renderResponseWithMesssageContent(status, msg);
                }
                if (!entities.isEmpty()) {
                    response = renderEntitiesResponse(entities, status);
                }
            }

            if (response == null) {
                msg = "Cannot parse the object to application/json representation.";
                MessageJson msgJson = new MessageJson();
                msgJson.setMessage(msg);
                msgJson.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
                response = renderResponseWithMesssageContent(Response.Status.INTERNAL_SERVER_ERROR, msgJson.toStringJson());
            }

            return response;

        } catch (JsonProcessingException ex) {
            msg = "Error while parsing the response to application/json representation";
            MessageJson msgJson = new MessageJson();
            msgJson.setMessage(msg + " --> " + ex.getMessage());
            msgJson.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            try {
                response = Response.status(status)
                        .header("Server", Constants.OCCI_SERVER_HEADER)
                        .header("Accept", getAcceptedTypes())
                        .entity(msgJson.toStringJson())
                        .type(Constants.MEDIA_TYPE_JSON)
                        .status(Response.Status.INTERNAL_SERVER_ERROR)
                        .build();
                return response;
            } catch (JsonProcessingException e) {
                throw new ResponseParseException(e.getMessage());
            }
        }
    }

    private Response renderResponseWithMesssageContent(Response.Status status, String msg) {
        Response response;
        response = Response.status(status)
                .header("Server", Constants.OCCI_SERVER_HEADER)
                .header("Accept", getAcceptedTypes())
                .entity(msg)
                .type(Constants.MEDIA_TYPE_JSON)
                .build();
        return response;
    }

    private Response renderEntitiesResponse(List<Entity> entities, Response.Status status) throws JsonProcessingException {

        Response response;
        OcciMainJson mainJson = new OcciMainJson();
        List<ResourceJson> resources = new LinkedList<>();
        ResourceJson currentResource = null;
        LinkJson currentLink = null;
        List<LinkJson> links = new LinkedList<>();
        for (Entity entity : entities) {
            if (entity instanceof Link) {
                LinkJson linkJson = buildLinkJsonFromEntity(entity);
                currentLink = linkJson;
                links.add(linkJson);

            } else {
                ResourceJson resJson = buildResourceJsonFromEntity(entity);
                currentResource = resJson;
                resources.add(resJson);
            }
        }
        String msg;

        if (!resources.isEmpty()) {
            mainJson.setResources(resources);
        }
        if (!links.isEmpty()) {
            mainJson.setLinks(links);
        }
        msg = mainJson.toStringJson();

        if (entities.size() == 1 && !resources.isEmpty() && resources.size() == 1 && currentResource != null) {
            // One entity to render.
            LOGGER.info("One entity resource to render.");
            msg = currentResource.toStringJson();
        }
        if (entities.size() == 1 && resources.isEmpty() && !links.isEmpty() && links.size() == 1 && currentLink != null) {
            LOGGER.info("One entity link to render.");
            msg = currentLink.toStringJson();
        }
        response = renderResponseWithMesssageContent(status, msg);

        return response;
    }

    private ResourceJson buildResourceJsonFromEntity(final Entity entity) {
        ResourceJson resJson = new ResourceJson();
        Resource res = (Resource) entity;
        Kind kind = res.getKind();
        List<Mixin> mixins;
        List<String> mixinsStr = new LinkedList<>();
        List<LinkJson> links = new LinkedList<>();
        resJson.setKind(kind.getScheme() + kind.getTerm());
        resJson.setId(Constants.URN_UUID_PREFIX + res.getId());
        resJson.setTitle(res.getTitle());
        resJson.setSummary(res.getSummary());
        resJson.setLocation(ConfigurationManager.getLocation(entity));

        List<String> actionsStr = new LinkedList<>();
        String actionStr;
        for (Action action : kind.getActions()) {
            actionStr = action.getScheme() + action.getTerm();
            actionsStr.add(actionStr);
        }
        resJson.setActions(actionsStr);
        Map<String, Object> attributes = new LinkedHashMap<>();
        // Attributes.
        List<AttributeState> attrsState = res.getAttributes();
        for (AttributeState attr : attrsState) {
            String key = attr.getName();
            String val = attr.getValue();

            if (!key.equals(Constants.OCCI_CORE_SUMMARY) && !key.equals(Constants.OCCI_CORE_TITLE)
                    && !key.equals(Constants.OCCI_CORE_ID)) {

                String valStr = ConfigurationManager.getAttrValueStr(entity, key);
                Number valNumber = ConfigurationManager.getAttrValueNumber(entity, key);

                if (valStr != null) {
                    attributes.put(key, valStr);
                } else if (valNumber != null) {
                    attributes.put(key, valNumber);
                } else {
                    if (val != null) {
                        attributes.put(key, val);
                    }
                }
            }
            if (key.equals(Constants.OCCI_CORE_ID)) {
                if (val != null && !val.startsWith(Constants.URN_UUID_PREFIX)) {
                    val = Constants.URN_UUID_PREFIX + val;
                }
                attributes.put(key, val);
            }

        }
        resJson.setAttributes(attributes);

        mixins = res.getMixins();
        for (Mixin mixin : mixins) {
            String mixinStr = mixin.getScheme() + mixin.getTerm();
            mixinsStr.add(mixinStr);
        }
        resJson.setMixins(mixinsStr);
        // resources has links ?
        for (Link link : res.getLinks()) {
            LinkJson linkJson = buildLinkJsonFromEntity(link);
            links.add(linkJson);
        }
        if (!links.isEmpty()) {
            resJson.setLinks(links);
        }

        return resJson;
    }

    private LinkJson buildLinkJsonFromEntity(final Entity entity) {
        LinkJson linkJson = new LinkJson();
        Link link = (Link) entity;
        Kind kind;
        List<Mixin> mixins;
        List<String> mixinsStr = new LinkedList<>();
        List<Action> actions;
        List<String> actionsStr = new LinkedList<>();
        String actionStr;
        Map<String, Object> attributes = new LinkedHashMap<>();
        kind = link.getKind();
        linkJson.setKind(kind.getScheme() + kind.getTerm());
        linkJson.setId(Constants.URN_UUID_PREFIX + link.getId());
        linkJson.setTitle(link.getTitle());
        linkJson.setLocation(ConfigurationManager.getLocation(entity));
        actions = kind.getActions();
        for (Action action : actions) {
            actionStr = action.getScheme() + action.getTerm();
            actionsStr.add(actionStr);
        }
        linkJson.setActions(actionsStr);

        // Attributes.
        List<AttributeState> attrsState = link.getAttributes();
        for (AttributeState attr : attrsState) {
            String key = attr.getName();
            String val = attr.getValue();
            if (val != null) {
                if (!key.equals(Constants.OCCI_CORE_SUMMARY) && !key.equals(Constants.OCCI_CORE_TITLE)
                        && !key.equals(Constants.OCCI_CORE_ID)
                        && !key.equals(Constants.OCCI_CORE_SOURCE)
                        && !key.equals(Constants.OCCI_CORE_TARGET)) {

                    EDataType eAttrType = ConfigurationManager.getEAttributeType(entity, key);

                    if (eAttrType != null
                            && (eAttrType instanceof EEnum || eAttrType.getInstanceClass() == String.class)) {
                        // value with quote only for String and EEnum type.
                        attributes.put(key, val);
                    } else if (eAttrType == null) {
                        // Cant determine the type.
                        attributes.put(key, val);
                    } else {
                        // Not a string nor an enum val.
                        try {
                            Number num = Utils.parseNumber(val, eAttrType.getInstanceClassName());
                            attributes.put(key, num);
                        } catch (NumberFormatException ex) {
                            attributes.put(key, val);
                        }
                    }
                }
                if (key.equals(Constants.OCCI_CORE_ID)) {
                    if (!val.startsWith(Constants.URN_UUID_PREFIX)) {
                        val = Constants.URN_UUID_PREFIX + val;
                    }
                    attributes.put(key, val);
                }
            }
        }
        Resource resSrc = link.getSource();
        Resource resTarget = link.getTarget();
        SourceJson src = new SourceJson();
        TargetJson target = new TargetJson();
        String relativeLocation = ConfigurationManager.getLocation(resSrc);
        src.setKind(resSrc.getKind().getScheme() + resSrc.getKind().getTerm());
        if (!relativeLocation.startsWith("/")) {
            relativeLocation = "/" + relativeLocation;
        }
        src.setLocation(relativeLocation);
        relativeLocation = ConfigurationManager.getLocation(resTarget);
        target.setKind(resTarget.getKind().getScheme() + resTarget.getKind().getTerm());
        if (!relativeLocation.startsWith("/")) {
            relativeLocation = "/" + relativeLocation;
        }
        target.setLocation(relativeLocation);
        linkJson.setSource(src);
        linkJson.setTarget(target);

        linkJson.setAttributes(attributes);

        mixins = link.getMixins();
        for (Mixin mixin : mixins) {
            String mixinStr = mixin.getScheme() + mixin.getTerm();
            mixinsStr.add(mixinStr);
        }
        linkJson.setMixins(mixinsStr);

        return linkJson;
    }

    @Override
    public Response getInterface(String categoryFilter, String user) {
        super.getInterface(categoryFilter, user);
        StringBuilder sb;
        Response response;
        List<Kind> kinds = getKindsConf();
        List<Mixin> mixins = getMixinsConf();

        if (kinds.isEmpty() && mixins.isEmpty()) {
            LOGGER.warn("No kinds and no mixin to render on interface /-/, if you use a filter this may be not found on the current configuration.");
            response = Response.noContent().build();
            return response;
        }

        sb = new StringBuilder();
        List<Mixin> extUserTagMixins = new LinkedList<>();
        List<ModelInterfaceJson> models = new LinkedList<>();


        // Build the list of mixins user tags.
        for (Mixin mixin : mixins) {
            if (ConfigurationManager.isMixinTags(user, mixin.getScheme() + mixin.getTerm())) {
                extUserTagMixins.add(mixin);
            }
        }

        try {
            buildKindsModelsJsonInterface(kinds, models, user);
            buildMixinsModelsJsonInterface(mixins, extUserTagMixins, models, user);

            GlobalModelInterfaceJson globalModelInterfaceJson = new GlobalModelInterfaceJson();
            globalModelInterfaceJson.setModel(models);
            sb.append(globalModelInterfaceJson.toStringJson());

        } catch (JsonProcessingException | ConfigurationException ex) {
            LOGGER.error("Exception thrown when rendering json interface : " + ex.getClass().getName() + " : " + ex.getMessage());
            sb = new StringBuilder();
        }

        String msg = sb.toString();
        if (!msg.isEmpty()) {
            response = Response.ok().entity(msg)
                    .header("Server", Constants.OCCI_SERVER_HEADER)
                    .type(Constants.MEDIA_TYPE_JSON_OCCI)
                    .header("Accept", getAcceptedTypes())
                    .build();
        } else {
            // May not be called.
            response = Response.noContent().build();
        }

        return response;
    }

    /**
     *
     * @param mixins
     * @param extUserTagMixins
     * @param models
     * @throws ConfigurationException
     */
    private void buildMixinsModelsJsonInterface(List<Mixin> mixins, List<Mixin> extUserTagMixins, List<ModelInterfaceJson> models, final String user) throws ConfigurationException {
        if (mixins.isEmpty()) {
            return;
        }
        boolean found;
        List<ActionInterfaceJson> actionsDefinitionJson;
        List<String> actions;

        for (Mixin mixin : mixins) {
            actionsDefinitionJson = new LinkedList<>();
            actions = new LinkedList<>();
            MixinInterfacejson mixinInterfaceJson = new MixinInterfacejson();
            mixinInterfaceJson.setLocation(ConfigurationManager.getLocation(mixin));
            mixinInterfaceJson.setScheme(mixin.getScheme());
            mixinInterfaceJson.setTerm(mixin.getTerm());
            mixinInterfaceJson.setTitle(mixin.getTitle());
            mixinInterfaceJson.setAttributes(buildAttributesInterface(mixin.getAttributes()));

            // Array of string actions.
            if (mixin.getActions() != null && !mixin.getActions().isEmpty()) {
                for (Action action : mixin.getActions()) {
                    actions.add(action.getScheme() + action.getTerm());
                    // Build action definition.
                    ActionInterfaceJson actionInterfaceJson = new ActionInterfaceJson();
                    actionInterfaceJson.setTitle(action.getTitle());
                    actionInterfaceJson.setScheme(action.getScheme());
                    actionInterfaceJson.setTerm(action.getTerm());
                    actionInterfaceJson.setAttributes(buildAttributesInterface(action.getAttributes()));
                    actionsDefinitionJson.add(actionInterfaceJson);
                }
            }
            mixinInterfaceJson.setActions(actions);

            ModelInterfaceJson modelJson = null;
            if (extUserTagMixins.contains(mixin)) {
                if (models.isEmpty()) {
                    modelJson = new ModelInterfaceJson();
                    modelJson.setId(mixin.getScheme());
                    models.add(modelJson);
                }
                for (ModelInterfaceJson model : models) {
                    if (model.getId() != null && !model.getId().isEmpty()
                            && model.getId().equals(mixin.getScheme())) {
                        modelJson = model;
                    }
                }
                if (modelJson == null) {
                    modelJson = new ModelInterfaceJson();
                    modelJson.setId(mixin.getScheme());
                    models.add(modelJson);
                }
            } else {
                Extension ext = ConfigurationManager.getExtensionForMixin(user, mixin.getScheme() + mixin.getTerm());
                if (models.isEmpty()) {
                    modelJson = new ModelInterfaceJson();
                    modelJson.setId(ext.getScheme());
                    models.add(modelJson);
                } else {
                    for (ModelInterfaceJson model : models) {
                        if (model.getId() != null && !model.getId().isEmpty()
                                && model.getId().equals(ext.getScheme())) {
                            modelJson = model;
                            break;
                        }
                    }
                    if (modelJson == null) {
                        modelJson = new ModelInterfaceJson();
                        modelJson.setId(ext.getScheme());
                        models.add(modelJson);
                    }

                }
            }

            modelJson.getMixins().add(mixinInterfaceJson);

            for (ActionInterfaceJson action : actionsDefinitionJson) {
                List<ActionInterfaceJson> actionsDef = modelJson.getActions();
                found = false;
                for (ActionInterfaceJson actionDef : actionsDef) {
                    if ((actionDef.getScheme() + actionDef.getTerm()).equals(action.getScheme() + action.getTerm())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    modelJson.getActions().add(action);
                }
            }


        }

    }

    /**
     *
     * @param kinds
     * @param models
     * @param user
     * @throws ConfigurationException
     */
    private void buildKindsModelsJsonInterface(final List<Kind> kinds, List<ModelInterfaceJson> models, final String user) throws ConfigurationException {
        if (kinds.isEmpty()) {
            return;
        }
        List<ActionInterfaceJson> actionsDefinitionJson;
        List<String> actions;
        boolean found;
        for (Kind kind : kinds) {
            actionsDefinitionJson = new LinkedList<>();
            actions = new LinkedList<>();
            KindInterfaceJson kindInterfaceJson = new KindInterfaceJson();
            kindInterfaceJson.setLocation(ConfigurationManager.getLocation(kind));
            if (kind.getParent() != null) {
                kindInterfaceJson.setParent(kind.getParent().getScheme() + kind.getParent().getTerm());
            }
            kindInterfaceJson.setScheme(kind.getScheme());
            kindInterfaceJson.setTerm(kind.getTerm());
            kindInterfaceJson.setTitle(kind.getTitle());

            kindInterfaceJson.setAttributes(buildAttributesInterface(kind.getAttributes()));

            // Array of string actions.
            if (kind.getActions() != null && !kind.getActions().isEmpty()) {
                for (Action action : kind.getActions()) {
                    actions.add(action.getScheme() + action.getTerm());
                    // Build action definition.
                    ActionInterfaceJson actionInterfaceJson = new ActionInterfaceJson();
                    actionInterfaceJson.setTitle(action.getTitle());
                    actionInterfaceJson.setScheme(action.getScheme());
                    actionInterfaceJson.setTerm(action.getTerm());
                    actionInterfaceJson.setAttributes(buildAttributesInterface(action.getAttributes()));
                    actionsDefinitionJson.add(actionInterfaceJson);
                }
            }
            kindInterfaceJson.setActions(actions);
            ModelInterfaceJson modelJson = null;
            Extension ext = ConfigurationManager.getExtensionForKind(user, kind.getScheme() + kind.getTerm());

            if (models.isEmpty()) {
                modelJson = new ModelInterfaceJson();
                modelJson.setId(ext.getScheme());
                models.add(modelJson);
            } else {
                for (ModelInterfaceJson model : models) {
                    if (model.getId() != null && !model.getId().isEmpty()
                            && model.getId().equals(ext.getScheme())) {
                        modelJson = model;
                    }
                }
                if (modelJson == null) {
                    modelJson = new ModelInterfaceJson();
                    modelJson.setId(ext.getScheme());
                    models.add(modelJson);
                }
            }
            modelJson.getKinds().add(kindInterfaceJson);
            for (ActionInterfaceJson action : actionsDefinitionJson) {
                List<ActionInterfaceJson> actionsDef = modelJson.getActions();
                found = false;
                for (ActionInterfaceJson actionDef : actionsDef) {
                    if ((actionDef.getScheme() + actionDef.getTerm()).equals(action.getScheme() + action.getTerm())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    modelJson.getActions().add(action);
                }
            }


        }



    }

    /**
     * Build a collection of attributes json rendering interface.
     * @param attributes
     * @return
     */
    private Map<String,AttributeInterfaceJson> buildAttributesInterface(EList<Attribute> attributes) {

        Map<String, AttributeInterfaceJson> attributesToReturn = new HashMap<>();
        String attrName;
        boolean mutable;
        boolean required;
        String type;
        String description;
        Object defaultObj;
        String defaultStr;
        String typeName;
        for (Attribute attribute : attributes) {
            mutable = attribute.isMutable();
            required = attribute.isRequired();
            attrName = attribute.getName();
            description = attribute.getDescription();
            defaultObj = attribute.getDefault();
            defaultStr = attribute.getDefault();

            // pattern value.
            EDataType dataType = attribute.getType();
            if (dataType != null) {
                type = attribute.getType().getInstanceTypeName();

                if (type == null) {
                    type = convertTypeToSchemaType(attribute.getType().getName());
                    typeName = attribute.getType().getName();
                } else {
                    typeName = type;
                    type = convertTypeToSchemaType(type);
                }
            } else {
                type = convertTypeToSchemaType(null);
                typeName = type;
            }
            AttributeInterfaceJson attrJson = new AttributeInterfaceJson();
            attrJson.setType(type);
            attrJson.setMutable(mutable);
            attrJson.setRequired(required);
            attrJson.setDescription(description);
            if (!type.equals("string") && defaultObj != null) {
                if (type.equals("number")) {
                    try {
                        defaultObj = convertStringToNumber(defaultStr, typeName);
                    } catch (NumberFormatException ex) {
                        LOGGER.error("Number conversion error : " + ex.getMessage() + " default value to convert: " + defaultStr + " for type: " + typeName);
                    }
                }
                if (type.equals("boolean")) {
                    defaultObj = Boolean.valueOf(defaultStr);
                }
                attrJson.setDefaultObj(defaultObj);
            } else {
                attrJson.setDefaultObj(defaultObj);
            }
            // Build pattern property.
            if (!type.equals("string")) {
                attrJson.setPatternType(type);
                attrJson.setPatternPattern("");
            }
            attributesToReturn.put(attrName, attrJson);
        }

        return attributesToReturn;
    }

    /**
     * Convert a type to a generic type understood by schema validation.
     *
     * @param typeName
     * @return
     */
    private String convertTypeToSchemaType(final String typeName) {
        String type;
        if (typeName == null) {
            return "string"; // default type.
        }
        switch (typeName.toLowerCase()) {
            case "integer":
            case "float":
            case "int":
            case "long":
            case "double":
            case "bigdecimal":
                type = "number";
                break;
            case "list":
            case "set":
            case "collection":
            case "map":
                type = "array";
                break;
            case "boolean":
                type = "boolean";
                break;
            case "string":
            case "":
                type = "string";
                break;
            default:
                type = "string";
        }
        return type;
    }

    /**
     * Usage with render json interface, for default value rendering.
     * @param value
     * @param typeName
     * @return
     */
    private Number convertStringToNumber(final String value, final String typeName) {
        if (typeName == null) {
            return null;
        }
        Number number = null;
        try {
            switch (typeName.toLowerCase()) {
                case "integer":
                case "int":
                    number = Integer.valueOf(value);
                    break;

                case "float":
                case "double":
                    number = Double.valueOf(value);
                    break;
                case "long":
                    number = Long.valueOf(value);
                    break;
                case "bigdecimal":
                    number = new BigDecimal(value);
                    break;
            }
            return number;
        } catch (NumberFormatException ex) {
            LOGGER.error("Cant convert the string: " + value + " to a valid number.");
            throw ex;
        }

    }

    /**
     * Remove the last character if defined by comma.
     *
     * @param sb
     * @return
     */
    private StringBuilder removeLastComma(final StringBuilder sb) {
        StringBuilder sbToReturn = sb;
        String tmp = sb.toString();
        if (tmp.endsWith(",")) {
            // remove the last comma.
            tmp = tmp.substring(0, tmp.length() - 1);
            sbToReturn = new StringBuilder(tmp);
        }
        return sbToReturn;
    }


    @Override
    public Response parseEmptyResponse(final Response.Status status) {
        return super.parseEmptyResponse(status, Constants.MEDIA_TYPE_JSON);
    }

}
