package com.paulhammant.servirtium.undertow;

import com.paulhammant.servirtium.InteractionManipulations;
import com.paulhammant.servirtium.Interactor;
import com.paulhammant.servirtium.ServerMonitor;
import com.paulhammant.servirtium.ServiceResponse;
import com.paulhammant.servirtium.ServirtiumServer;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import static com.paulhammant.servirtium.JsonAndXmlUtilities.prettifyDocOrNot;

public class UndertowServirtiumServer extends ServirtiumServer {

    private Undertow undertowServer;
    private Interactor interactor;
    private boolean failed = false;

    public UndertowServirtiumServer(ServerMonitor monitor, int port, boolean ssl,
                                    InteractionManipulations interactionManipulations,
                                    Interactor interactor) {
        this.interactor = interactor;

        undertowServer = Undertow.builder()
                .addHttpListener(port, "localhost")
                .setHandler(new BlockingHandler(exchange ->
                        UndertowServirtiumServer.this.handleExchange(exchange, monitor, interactionManipulations)))
                .build();
    }

    private void handleExchange(HttpServerExchange exchange, ServerMonitor monitor, InteractionManipulations interactionManipulations) throws IOException {
        bumpInteractionNum();

        String method = exchange.getRequestMethod().toString();

        String uri = exchange.getRequestURI();
        String url = exchange.getRequestURL();

        // Fixes for Proxy server case - Jetty and Undertow are different here.
        if (uri.startsWith("https://") || uri.startsWith("http://")) {
            uri = uri.substring(url.indexOf("/",7));
        }

        url = (url.startsWith("http://") || url.startsWith("https://")) ? url : "http://" + exchange.getHostAndPort() + uri;

        //String clientRequestBody = "";
        List<String> clientRequestHeaders = new ArrayList<>();

        try {

            if (method.equals("CONNECT")) {
                exchange.getResponseSender().send("Servirtium does not support CONNECT yet");
                exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, "text/plain");
                exchange.setStatusCode(500);
                return;
            }

            Interactor.Interaction interaction = interactor.newInteraction(method, uri, getInteractionNum(), url, getContext());

            monitor.interactionStarted(getInteractionNum(), interaction);

            final HeaderValues headerValues = exchange.getRequestHeaders().get(Headers.CONTENT_TYPE_STRING);
            String clientRequestContentType;
            if (headerValues == null) {
                clientRequestContentType = "";
            } else {
                clientRequestContentType = headerValues.getFirst();
                ;

            }

//                    if (isText(contentType)) {
//                        BufferedReader reader = baseRequest.getReader();
//                        clientRequestBody = reader.lines().collect(Collectors.joining("\n"));
//                    } else {
//                        ServletInputStream is = baseRequest.getInputStream();
//                        clientRequestBody = new byte[is.available()];
//
//                    }
//

            final String requestUrl = prepareHeadersAndBodyForReal(exchange, method, url, clientRequestHeaders, interaction, clientRequestContentType, interactionManipulations);

            // INTERACTION
            ServiceResponse realResponse = interactor.getServiceResponseForRequest(method, requestUrl, clientRequestHeaders, interaction, getLowerCaseHeaders());

            realResponse = processHeadersAndBodyBackFromReal(interaction, realResponse, interactionManipulations);

            interactor.addInteraction(interaction);

            exchange.setStatusCode(realResponse.statusCode);

            for (String header : realResponse.headers) {
                int ix = header.indexOf(": ");
                String hdrKey = header.substring(0, ix);
                String hdrVal = header.substring(ix + 2);
                exchange.getResponseHeaders().add(new HttpString(hdrKey), hdrVal);
            }

            if (realResponse.contentType != null) {
                exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, realResponse.contentType);
            }

            if (realResponse.body instanceof String) {
                exchange.getResponseSender().send((String) realResponse.body);
            } else {
                exchange.getOutputStream().write((byte[]) realResponse.body);
            }

            monitor.interactionFinished(getInteractionNum(), method, url, getContext());
        } catch (AssertionError assertionError) {
            failed = true;
            exchange.setStatusCode(500);
            exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Servirtium Server AssertionError: " + assertionError.getMessage());
            monitor.interactionFailed(getInteractionNum(), method, url, assertionError, getContext());
        } catch (Throwable throwable) {
            failed = true;
            exchange.setStatusCode(500);
            exchange.getResponseHeaders().add(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Servirtium Server unexpected Throwable: " + throwable.getMessage());
            monitor.unexpectedRequestError(throwable, getContext());
            throw throwable; // stick your debugger here
        } finally {
        }
    }

    private ServiceResponse processHeadersAndBodyBackFromReal(Interactor.Interaction interaction, ServiceResponse realResponse, InteractionManipulations interactionManipulations) {
        ArrayList<String > newHeaders = new ArrayList<>();
        for (int i = 0; i < realResponse.headers.length; i++) {
            String headerBackFromReal = realResponse.headers[i];
            String potentiallyChangedHeader = interactionManipulations.changeSingleHeaderReturnedBackFromServer(i, headerBackFromReal);
            if (potentiallyChangedHeader != null) {
                newHeaders.add(potentiallyChangedHeader);
            }
        }
        interactionManipulations.changeAllHeadersReturnedBackFromServer(newHeaders);

        if (realResponse.body instanceof String) {
            realResponse = realResponse.withRevisedBody(interactionManipulations.changeBodyReturnedBackFromServer((String) realResponse.body));
            // recreate response

            if (shouldHavePrettyPrintedTextBodies()) {
                String body = prettifyDocOrNot((String) realResponse.body);
                if (!body.equals(realResponse.body)) {
//                                realResponse.headers
                    realResponse = realResponse.withRevisedBody(body);
                    ArrayList<String> tmp = new ArrayList<>();
                    for (String header : newHeaders) {
                        if (header.startsWith("Content-Length")) {
                            tmp.add("Content-Length: " + body.length());
                        } else {
                            tmp.add(header);
                        }
                    }
                    newHeaders = tmp;
                }
            }
        }

        realResponse = realResponse.withRevisedHeaders(newHeaders.toArray(new String[0]));

        interaction.noteResponseHeadersAndBody(realResponse.headers, realResponse.body, realResponse.statusCode, realResponse.contentType);

        return realResponse;
    }

    private String prepareHeadersAndBodyForReal(HttpServerExchange exchange, String method, String url, List<String> clientRequestHeaders, Interactor.Interaction interaction, String clientRequestContentType, InteractionManipulations interactionManipulations) throws IOException {

        exchange.startBlocking();
        InputStream is = exchange.getInputStream();

        Object clientRequestBody = null;

        if (is.available() > 0) {

            if (isText(clientRequestContentType)) {
                clientRequestBody = null;
                String characterEncoding = exchange.getRequestCharset();
                if (characterEncoding == null) {
                    characterEncoding = "utf-8";
                }
                try (Scanner scanner = new Scanner(is, characterEncoding)) {
                    clientRequestBody = scanner.useDelimiter("\\A").next();
                }
                if (shouldHavePrettyPrintedTextBodies() && clientRequestBody != null) {
                    clientRequestBody = prettifyDocOrNot((String) clientRequestBody);
                }
            } else {
                byte[] targetArray = new byte[is.available()];
                is.read(targetArray);
                clientRequestBody = targetArray;
                ;
            }
        }

        exchange.getRequestHeaders().forEach(header -> {
            String hdrName = header.getHeaderName().toString();
            header.forEach(hdrVal -> {
                hdrVal = interactionManipulations.headerReplacement(hdrName, hdrVal);
                final String newHeader = (getLowerCaseHeaders() ? hdrName.toLowerCase() : hdrName) + ": " + hdrVal;
                clientRequestHeaders.add(newHeader);
                interactionManipulations.changeSingleHeaderForRequestToServer(method, newHeader, clientRequestHeaders);
            });
        });

        interactionManipulations.changeAllHeadersForRequestToServer(clientRequestHeaders);

        if (clientRequestBody instanceof String) {
            clientRequestBody = interactionManipulations.changeBodyForRequestToServer((String) clientRequestBody);
        }

        if (clientRequestBody == null) {
            clientRequestBody = "";
        }

        interaction.noteClientRequestHeadersAndBody(clientRequestHeaders, clientRequestBody, clientRequestContentType);

        return interactionManipulations.changeUrlForRequestToServer(url);
    }

    public ServirtiumServer start() throws Exception {
        undertowServer.start();
        return this;
    }

    public void stop() {
        try {
            interactor.finishedScript(getInteractionNum(), failed); // just in case
        } finally {
            undertowServer.stop();
        }
    }

    public void finishedScript() {
        interactor.finishedScript(getInteractionNum(), failed);
    }


}
