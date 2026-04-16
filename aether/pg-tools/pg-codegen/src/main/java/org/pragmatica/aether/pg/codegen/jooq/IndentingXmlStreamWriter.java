// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.jooq;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;


/// Delegating XMLStreamWriter that adds indentation for human-readable output.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"}) final class IndentingXmlStreamWriter implements XMLStreamWriter {
    private static final String INDENT = "  ";

    private static final String NEWLINE = "\n";

    private final XMLStreamWriter delegate;

    private int depth = 0;

    private boolean hasChildElement = false;

    private boolean hasText = false;

    IndentingXmlStreamWriter(XMLStreamWriter delegate) {
        this.delegate = delegate;
    }

    @Override public void writeStartElement(String localName) throws XMLStreamException {
        writeIndent();
        delegate.writeStartElement(localName);
        depth++;
        hasChildElement = false;
        hasText = false;
    }

    @Override public void writeEndElement() throws XMLStreamException {
        depth--;
        if (hasChildElement) {writeIndent();}
        delegate.writeEndElement();
        hasChildElement = true;
        hasText = false;
    }

    @Override public void writeCharacters(String text) throws XMLStreamException {
        delegate.writeCharacters(text);
        hasText = true;
    }

    @Override public void writeCharacters(char[] text, int start, int len) throws XMLStreamException {
        delegate.writeCharacters(text, start, len);
        hasText = true;
    }

    @Override public void writeStartDocument() throws XMLStreamException {
        delegate.writeStartDocument();
    }

    @Override public void writeStartDocument(String version) throws XMLStreamException {
        delegate.writeStartDocument(version);
    }

    @Override public void writeStartDocument(String encoding, String version) throws XMLStreamException {
        delegate.writeStartDocument(encoding, version);
        delegate.writeCharacters(NEWLINE);
    }

    @Override public void writeEndDocument() throws XMLStreamException {
        delegate.writeEndDocument();
    }

    @Override public void writeDefaultNamespace(String namespaceURI) throws XMLStreamException {
        delegate.writeDefaultNamespace(namespaceURI);
    }

    @Override public void writeEmptyElement(String localName) throws XMLStreamException {
        writeIndent();
        delegate.writeEmptyElement(localName);
        hasChildElement = true;
    }

    private void writeIndent() throws XMLStreamException {
        delegate.writeCharacters(NEWLINE);
        delegate.writeCharacters(INDENT.repeat(depth));
    }

    @Override public void writeStartElement(String namespaceURI, String localName) throws XMLStreamException {
        writeIndent();
        delegate.writeStartElement(namespaceURI, localName);
        depth++;
        hasChildElement = false;
        hasText = false;
    }

    @Override public void writeStartElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
        writeIndent();
        delegate.writeStartElement(prefix, localName, namespaceURI);
        depth++;
        hasChildElement = false;
        hasText = false;
    }

    @Override public void writeEmptyElement(String namespaceURI, String localName) throws XMLStreamException {
        delegate.writeEmptyElement(namespaceURI, localName);
    }

    @Override public void writeEmptyElement(String prefix, String localName, String namespaceURI) throws XMLStreamException {
        delegate.writeEmptyElement(prefix, localName, namespaceURI);
    }

    @Override public void writeAttribute(String localName, String value) throws XMLStreamException {
        delegate.writeAttribute(localName, value);
    }

    @Override public void writeAttribute(String prefix, String namespaceURI, String localName, String value) throws XMLStreamException {
        delegate.writeAttribute(prefix, namespaceURI, localName, value);
    }

    @Override public void writeAttribute(String namespaceURI, String localName, String value) throws XMLStreamException {
        delegate.writeAttribute(namespaceURI, localName, value);
    }

    @Override public void writeNamespace(String prefix, String namespaceURI) throws XMLStreamException {
        delegate.writeNamespace(prefix, namespaceURI);
    }

    @Override public void writeComment(String data) throws XMLStreamException {
        delegate.writeComment(data);
    }

    @Override public void writeProcessingInstruction(String target) throws XMLStreamException {
        delegate.writeProcessingInstruction(target);
    }

    @Override public void writeProcessingInstruction(String target, String data) throws XMLStreamException {
        delegate.writeProcessingInstruction(target, data);
    }

    @Override public void writeCData(String data) throws XMLStreamException {
        delegate.writeCData(data);
    }

    @Override public void writeDTD(String dtd) throws XMLStreamException {
        delegate.writeDTD(dtd);
    }

    @Override public void writeEntityRef(String name) throws XMLStreamException {
        delegate.writeEntityRef(name);
    }

    @Override public void close() throws XMLStreamException {
        delegate.close();
    }

    @Override public void flush() throws XMLStreamException {
        delegate.flush();
    }

    @Override public void setPrefix(String prefix, String uri) throws XMLStreamException {
        delegate.setPrefix(prefix, uri);
    }

    @Override public void setDefaultNamespace(String uri) throws XMLStreamException {
        delegate.setDefaultNamespace(uri);
    }

    @Override public void setNamespaceContext(NamespaceContext context) throws XMLStreamException {
        delegate.setNamespaceContext(context);
    }

    @Override public NamespaceContext getNamespaceContext() {
        return delegate.getNamespaceContext();
    }

    @Override public Object getProperty(String name) {
        return delegate.getProperty(name);
    }

    @Override public String getPrefix(String uri) throws XMLStreamException {
        return delegate.getPrefix(uri);
    }
}
