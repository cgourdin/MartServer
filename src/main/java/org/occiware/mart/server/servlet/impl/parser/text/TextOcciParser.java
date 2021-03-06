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
package org.occiware.mart.server.servlet.impl.parser.text;

import org.occiware.clouddesigner.occi.*;
import org.occiware.mart.server.servlet.exception.AttributeParseException;
import org.occiware.mart.server.servlet.exception.CategoryParseException;
import org.occiware.mart.server.servlet.exception.ResponseParseException;
import org.occiware.mart.server.servlet.facade.AbstractRequestParser;
import org.occiware.mart.server.servlet.impl.parser.json.utils.InputData;
import org.occiware.mart.server.servlet.model.ConfigurationManager;
import org.occiware.mart.server.servlet.utils.Constants;
import org.occiware.mart.server.servlet.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.regex.Matcher;

/**
 * text/occi rendering are in headers not in body, this is not a text/plain (or
 * text/occi+plain).
 *
 * @author Christophe Gourdin
 */
public class TextOcciParser extends AbstractRequestParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextOcciParser.class);

    private final InputData data = new InputData();

    @Override
    public void parseOcciCategories(HttpHeaders headers, HttpServletRequest request) throws CategoryParseException {
        MultivaluedMap<String, String> map = headers.getRequestHeaders();

        List<String> values;
        List<String> mixinsToAdd = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String key = entry.getKey();
            values = entry.getValue();
            if (key.equals(Constants.CATEGORY)) {
                // Check the class value.
                // check if this is a kind or a mixin. 
                // As it may have kind and mixins, the value return will be a kind class before.
                // Example of value: 
                // compute; scheme="http://schemas.ogf.org/occi/infrastructure#"; class="kind";

                for (String value : values) {
                    String[] valuesArray = value.split(",");

                    for (String line : valuesArray) {
                        if (line.startsWith(" ")) {
                            line = Constants.CATEGORY + ":" + line;
                        } else {
                            line = Constants.CATEGORY + ": " + line;
                        }
                        // String line = Constants.CATEGORY + ": " + value;
                        Matcher matcher = Constants.PATTERN_CATEGORY.matcher(line);
                        if (!matcher.find()) {
                            continue;
                        }
                        String term = matcher.group(Constants.GROUP_TERM);
                        String scheme = matcher.group(Constants.GROUP_SCHEME);
                        String categoryClass = matcher.group(Constants.GROUP_CLASS);

                        if (categoryClass.equalsIgnoreCase(Constants.CLASS_KIND)) {
                            // Assign the kind.
                            data.setKind(scheme + term);
                            continue;
                        }
                        if (categoryClass.equalsIgnoreCase(Constants.CLASS_MIXIN)) {
                            if (matcher.group(Constants.GROUP_LOCATION) != null) {
                                // is a mixin tag.
                                data.setLocation(matcher.group(Constants.GROUP_LOCATION));
                                data.setMixinTag(scheme + term);

                            } else {
                                // is a simple mixin.
                                mixinsToAdd.add(scheme + term);
                            }
                            continue;
                        }
                        if (categoryClass.equalsIgnoreCase(Constants.CLASS_ACTION)) {
                            data.setAction(scheme + term);
                        }
                    }
                }
            }
        }
        if (!mixinsToAdd.isEmpty()) {
            data.setMixins(mixinsToAdd);
        }
        // Update the data in the list of input datas. for this parser, there is only one inputdata.
        updateInputDatas();
    }

    private void updateInputDatas() {
        List<InputData> inputDatas = getInputDatas();

        if (inputDatas.isEmpty()) {
            inputDatas.add(data);
            setInputDatas(inputDatas);
        } else {
            inputDatas.clear();
            inputDatas.add(data);
        }
    }

    /**
     * Convert X-OCCI-Attribute to map key --> value.
     *
     * @param headers
     * @param request
     * @throws org.occiware.mart.server.servlet.exception.AttributeParseException
     */
    @Override
    public void parseOcciAttributes(HttpHeaders headers, HttpServletRequest request) throws AttributeParseException {
        Map<String, String> attrs = new HashMap<>();
        MultivaluedMap<String, String> map = headers.getRequestHeaders();
        List<String> values;
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            String key = entry.getKey();
            values = entry.getValue();
            if (key.equalsIgnoreCase(Constants.X_OCCI_ATTRIBUTE)) {
                for (String value : values) {
                    // Multiple value on X_OCCI_Attribute header may exist on a same declaration. (source and target for example).
                    // like this --> X-OCCI-Attribute: occi.core.source="/compute/f88486b7-0632-482d-a184-a9195733ddd0", occi.core.target="/network/network1".
                    String[] valuesTmp = value.split(",");
                    for (String valueTmp : valuesTmp) {
                        // Parse the value:
                        String[] attr = valueTmp.split("=");
                        if (attr.length > 1) {
                            attr[0] = attr[0].replace("\"", "");
                            if (attr[0].startsWith(" ")) {
                                attr[0] = attr[0].substring(1); // remove starting space.
                            }
                            attr[1] = attr[1].replace("\"", "");
                            attrs.put(attr[0], attr[1]);
                        }
                    }

                }
            }
            if (key.equalsIgnoreCase(Constants.X_OCCI_LOCATION)) {
                for (String value : values) {
                    String[] valuesTmp = value.split(",");
                    for (String valueTmp : valuesTmp) {
                        data.addXocciLocation(valueTmp);
                    }

                    break;
                }
            }
        }
        data.setAttrs(attrs);
        // Update the data in the list of input datas. for this parser, there is only one inputdata.
        updateInputDatas();
    }

    /**
     * Parse a text/occi Response.
     *
     * @param object
     * @param status
     * @return
     * @throws ResponseParseException
     */
    @Override
    public Response parseResponse(Object object, Response.Status status) throws ResponseParseException {
        Response response = null;
        String msg;
        // Case 1 : Object is a Response object.
        if (object instanceof Response) {
            if (status != null && status.equals(Response.Status.OK)) {
                msg = "ok \n";

                response = renderObjResponse((Response) object, status, msg);
            } else {
                msg = "";
                response = renderObjResponse((Response) object, status, msg);
            }
        }
        // Case 2 : Object is a String.
        if (object instanceof String) {
            if (status != null && status.equals(Response.Status.OK)) {
                msg = "ok \n";
                response = renderObjResponse(msg, status);

            } else {
                response = renderObjResponse(object, status);
            }

        }
        if (object instanceof Entity) {
            // Build an object response from entity occiware object model.
            Entity entity = (Entity) object;
            response = renderEntityResponse(entity, status);
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
                Response.ResponseBuilder responseBuilder = Response.status(status)
                        .header("Server", Constants.OCCI_SERVER_HEADER)
                        .header("Accept", getAcceptedTypes())
                        .entity("ok \n")
                        .type(Constants.MEDIA_TYPE_TEXT_OCCI);
                for (String location : locations) {
                    String absLocation = getServerURI().toString() + location;
                    responseBuilder.header(Constants.X_OCCI_LOCATION, absLocation);
                }
                response = responseBuilder.build();
            }

            for (Entity entity : entities) {
                response = renderEntityResponse(entity, status);
                // We render only the first entity found, cause to limit size of header.
                break;
            }

        }

        if (response == null) {
            throw new ResponseParseException("Cannot parse the object to text/occi representation.");
        }

        return response;

    }

    private Response renderObjResponse(Response object, Response.Status status, String msg) {
        Response response;
        response = Response.fromResponse(object)
                .header("Server", Constants.OCCI_SERVER_HEADER)
                .header("Accept", getAcceptedTypes())
                .type(Constants.MEDIA_TYPE_TEXT_OCCI)
                .entity(msg)
                .status(status)
                .build();
        return response;
    }

    private Response renderObjResponse(Object object, Response.Status status) {
        Response response;
        response = Response.status(status)
                .header("Server", Constants.OCCI_SERVER_HEADER)
                .header("content", object)
                .header("Accept", getAcceptedTypes())
                .type(Constants.MEDIA_TYPE_TEXT_OCCI)
                .entity(object)
                .build();
        return response;
    }

    /**
     * Build interface /-/ for accept type : text/occi.
     *
     * @param categoryFilter (category Term)
     * @param user
     * @return interface to set in header.
     */
    @Override
    public Response getInterface(String categoryFilter, final String user) {
        // Define kindsConf and mixinsConf from configuration used extension kinds and mixins object.
        super.getInterface(categoryFilter, user);
        Response response;
        List<Kind> kinds = getKindsConf();

        List<Mixin> mixins = getMixinsConf();

        StringBuilder sb = renderOcciKindsActions(kinds, true);

        sb.append(renderOcciMixins(mixins, true));

        String msg = sb.toString();
        if (!msg.isEmpty()) {
            if (msg.getBytes().length < 8000) {

                // response = renderObjResponse("ok \n", Response.Status.OK);
                response = Response.ok().entity("ok \n")
                        .header("Server", Constants.OCCI_SERVER_HEADER)
                        .header("interface", sb.toString())
                        .type(Constants.MEDIA_TYPE_TEXT_OCCI)
                        .header("Accept", getAcceptedTypes())
                        .build();
            } else {
                response = renderObjResponse(sb.toString(), Response.Status.OK);
            }
        } else {
            // May not be called.
            response = Response.noContent().build();
        }
        return response;
    }

    /**
     * Get text/occi for occi Kinds and actions.
     *
     * @param kinds
     * @param detailed
     * @return
     */
    private StringBuilder renderOcciKindsActions(List<Kind> kinds, boolean detailed) {
        StringBuilder sb = new StringBuilder();

        for (Kind kind : kinds) {
            if (!kind.getScheme().equals(Constants.OCCI_CORE_SCHEME)) {
                renderOcciKind(kind, detailed, sb);
            }
        }
        return sb;
    }

    /**
     * Get text/occi for occi mixins and dependencies.
     *
     * @param mixins
     * @param detailed
     * @return
     */
    private StringBuilder renderOcciMixins(List<Mixin> mixins, boolean detailed) {
        StringBuilder sb = new StringBuilder();
        for (Mixin mixin : mixins) {
            renderOcciMixin(detailed, sb, mixin);

        }
        return sb;
    }

    private void renderOcciMixin(boolean detailed, StringBuilder sb, Mixin mixin) {
        sb.append(mixin.getTerm()).append(";").append(Constants.CRLF)
                .append("scheme=\"").append(mixin.getScheme()).append("\";").append(Constants.CRLF)
                .append("class=\"mixin\"").append(";");
        if (detailed) {
            sb.append(Constants.CRLF);
            sb.append("title=\"").append(mixin.getTitle()).append('\"').append(";").append(Constants.CRLF);
            List<Mixin> mixinsDep = mixin.getDepends();
            if (!mixinsDep.isEmpty()) {
                sb.append("rel=\"");
                String sep = "";
                for (Mixin md : mixinsDep) {
                    sb.append(sep).append(md.getScheme()).append(md.getTerm());
                    sep = " ";
                }
                sb.append('\"').append(";").append(Constants.CRLF);
            }
            appendCategoryLocation(sb, ConfigurationManager.getLocation(mixin));
            appendAttributes(sb, mixin.getAttributes());
            appendActions(sb, mixin.getActions());
        }
    }

    private void appendCategoryLocation(StringBuilder sb, String location) {
        sb.append("location=\"");
        sb.append(location);
        sb.append('\"');
        sb.append(";");
        sb.append(Constants.CRLF);
    }

    /**
     * Append attributes in text/occi format for kinds, mixins and entities
     * definition.
     *
     * @param sb
     * @param attributes
     */
    private void appendAttributes(StringBuilder sb, List<Attribute> attributes) {
        if (!attributes.isEmpty()) {
            sb.append("attributes=\"");
            String sep = "";
            for (Attribute attribute : attributes) {
                sb.append(sep).append(attribute.getName());
                if (attribute.isRequired() || !attribute.isMutable()) {
                    sb.append('{');
                    if (!attribute.isMutable()) {
                        sb.append("immutable");
                        if (attribute.isRequired()) {
                            sb.append(' ');
                        }
                    }
                    if (attribute.isRequired()) {
                        sb.append("required");
                    }
                    sb.append('}');
                }
                sep = " ";
            }
            sb.append('\"').append(";").append(Constants.CRLF);
        }
    }

    /**
     * Append action to string builder.
     *
     * @param sb
     * @param actions
     */
    private void appendActions(StringBuilder sb, List<Action> actions) {
        if (!actions.isEmpty()) {
            sb.append("actions=\"");
            String sep = "";
            for (Action action : actions) {
                sb.append(sep).append(action.getScheme()).append(action.getTerm());
                sep = " ";
            }
            sb.append('\"').append(";").append(Constants.CRLF);
        }
    }

    /**
     * Get the kind on header, for text/occi.
     *
     * @param headers
     * @return
     */
    public String getKindFromHeader(HttpHeaders headers) {
        String kind = null;

        List<String> kindsVal = Utils.getFromValueFromHeaders(headers, Constants.CATEGORY);
        // Search for Class="kind" value.
        String[] vals;
        boolean kindVal;
        for (String line : kindsVal) {
            kindVal = false;
            vals = line.split(";");
            // Check class="kind".
            for (String val : vals) {
                if (val.contains("class=\"" + Constants.CLASS_KIND + "\"")) {
                    kindVal = true;
                }
            }
            if (kindVal) {
                // Get the kind value.
                for (String val : vals) {
                    if (val.contains(Constants.CATEGORY)) {
                        String category = val.trim();

                        // Get the value.
                        kind = category.split(":")[1];
                        LOGGER.info("Kind value is : " + kind);
                    }
                }
            }
        }
        return kind;
    }

    /**
     * Render a response with entity object input.
     *
     * @param entity
     * @param status
     * @return a Response object conform to text/occi specification.
     */
    private Response renderEntityResponse(Entity entity, Response.Status status) {

        Response response;

        String categories = renderCategory(entity.getKind(), false);

        // if entity as mixins, update categories as expected.
        List<Mixin> mixinsTmp = entity.getMixins();
        for (Mixin mixin : mixinsTmp) {
            categories += renderCategory(mixin, false);
        }

        // Link header.
        String relativeLocation = ConfigurationManager.getLocation(entity);
        String absoluteEntityLocation = getServerURI().toString() + relativeLocation;

        // Convert all actions to links.
        javax.ws.rs.core.Link[] links = renderActionsLink(entity, absoluteEntityLocation);
        String msg = "ok \n";
        response = Response.status(status)
                .header("Server", Constants.OCCI_SERVER_HEADER)
                .header(Constants.CATEGORY, categories)
                .header(Constants.X_OCCI_ATTRIBUTE, renderAttributes(entity))
                .header(Constants.X_OCCI_LOCATION, renderXOCCILocationAttr(entity))
                .header("Accept", getAcceptedTypes())
                .type(Constants.MEDIA_TYPE_TEXT_OCCI)
                .entity(msg)
                .links(links)
                .build();
        return response;
    }

    /**
     * Render a category with its definitions attributes etc.
     *
     * @param kind
     * @param detailed
     * @return
     */
    private String renderCategory(Kind kind, boolean detailed) {
        StringBuilder sb = new StringBuilder();

        renderOcciKind(kind, detailed, sb);
        return sb.toString();
    }

    private void renderOcciKind(Kind kind, boolean detailed, StringBuilder sb) {
        sb.append(kind.getTerm()).append(";").append(Constants.CRLF)
                .append("scheme=\"").append(kind.getScheme()).append("\";").append(Constants.CRLF)
                .append("class=\"kind\"").append(";");
        if (detailed) {
            sb.append(Constants.CRLF);
            sb.append("title=\"").append(kind.getTitle()).append('\"').append(";").append(Constants.CRLF);
            Kind parent = kind.getParent();
            if (parent != null) {
                sb.append("rel=\"").append(parent.getScheme()).append(parent.getTerm()).append('\"').append(";").append(Constants.CRLF);
            }
            appendCategoryLocation(sb, ConfigurationManager.getLocation(kind));
            appendAttributes(sb, kind.getAttributes());
            appendActions(sb, kind.getActions());
        }
    }

    /**
     * As kind we render here a mixin.
     *
     * @param mixin
     * @param detailed
     * @return
     */
    private String renderCategory(Mixin mixin, boolean detailed) {
        StringBuilder sb = new StringBuilder();

        renderOcciMixin(detailed, sb, mixin);

        return sb.toString();
    }

    /**
     * Render Link: header (cf spec text rendering).
     *
     * @param entity
     * @param entityAbsolutePath
     * @return An array of Link to set to header.
     */
    private javax.ws.rs.core.Link[] renderActionsLink(final Entity entity, final String entityAbsolutePath) {
        LOGGER.info("Entity location : " + entityAbsolutePath);
        javax.ws.rs.core.Link linkAbsoluteEntityPath = javax.ws.rs.core.Link.fromUri(entityAbsolutePath)
                .title(entity.getKind().getTerm())
                .build();
        javax.ws.rs.core.Link[] links;
        int linkSize = 1;
        int current = 0;

        // For each actions we add the link like this : <mylocation?action=actionTerm>; \
        //    rel="http://actionScheme#actionTerm"
        List<Action> actionsTmp = entity.getKind().getActions();
        linkSize += actionsTmp.size();
        links = new javax.ws.rs.core.Link[linkSize];
        links[0] = linkAbsoluteEntityPath;
        current++;
        javax.ws.rs.core.Link actionLink;

        // We render the Link header.
        if (!actionsTmp.isEmpty()) {
            // We render the different link here.
            for (Action action : actionsTmp) {

                actionLink = javax.ws.rs.core.Link.fromUri(entityAbsolutePath)
                        .title(action.getTerm())
                        .rel(action.getScheme() + action.getTerm())
                        .build();
                links[current] = actionLink;
                current++;
            }
        }

        return links;
    }

    /**
     * Return a string like :
     * "http://myabsolutepathserver:xxxx/myentitylocationrelativepath". This
     * will added to header with X-OCCI-Location name field.
     *
     * @param entity
     * @return
     */
    private String renderXOCCILocationAttr(final Entity entity) {
        String location = ConfigurationManager.getLocation(entity);
        return getServerURI().toString() + location;
    }

    /**
     * Render attributes used for GET request on entity.
     *
     * @param entity
     * @return
     */
    private String renderAttributes(Entity entity) {
        String attribs = "";
        StringBuilder sb = new StringBuilder();
        List<AttributeState> attrStates = entity.getAttributes();
        String coreId = Constants.OCCI_CORE_ID + "=\"" + Constants.URN_UUID_PREFIX + entity.getId() + "\"," + Constants.CRLF;
        sb.append(coreId);
        if (entity instanceof Link) {
            Link link = (Link) entity;
            String source = Constants.OCCI_CORE_SOURCE + "=\"" + ConfigurationManager.getLocation(link.getSource()) + "\"" + "," + Constants.CRLF;
            sb.append(source);
            String target = Constants.OCCI_CORE_TARGET + "=\"" + ConfigurationManager.getLocation(link.getTarget()) + "\"" + "," + Constants.CRLF;
            sb.append(target);
        }

        for (AttributeState attribute : attrStates) {
            String name = attribute.getName();
            if (name.equals(Constants.OCCI_CORE_ID) || name.equals(Constants.OCCI_CORE_SOURCE) || name.equals(Constants.OCCI_CORE_TARGET)) {
                continue;
            }
            String value = null;

            String valStr = ConfigurationManager.getAttrValueStr(entity, name);
            Number valNumber = ConfigurationManager.getAttrValueNumber(entity, name);

            if (valStr != null) {
                value = "\"" + valStr + "\"";
            } else if (valNumber != null) {
                value = "" + valNumber;
            } else {
                if (attribute.getValue() != null) {
                    value = "\"" + attribute.getValue() + "\"";
                }
            }
            // if value is null, it wont display in header.
            if (value == null) {
                continue;
            }

            attribs += attribute.getName() + '=' + value + "," + Constants.CRLF;
        }

        if (!attribs.isEmpty()) {
            // To remove the last comma.
            attribs = attribs.substring(0, attribs.length() - 3);
            sb.append(attribs);
        }
        return sb.toString();
    }

    @Override
    public Response parseEmptyResponse(final Response.Status status) {
        return super.parseEmptyResponse(status, Constants.MEDIA_TYPE_TEXT_OCCI);
    }

}
