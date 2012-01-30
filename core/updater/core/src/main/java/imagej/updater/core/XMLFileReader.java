//
// XMLFileReader.java
//

/*
ImageJ software for multidimensional image processing and analysis.

Copyright (c) 2010, ImageJDev.org.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the names of the ImageJDev.org developers nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
*/

package imagej.updater.core;

import imagej.updater.core.FileObject.Status;
import imagej.updater.core.FilesCollection.UpdateSite;
import imagej.updater.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/*
 * XML File Reader reads an locally-cached index of the available file versions.
 */
public class XMLFileReader extends DefaultHandler {

	private final FilesCollection plugins;

	// this is the name of the update site (null means we read the local
	// db.xml.gz)
	protected String updateSite;

	// every plugin newer than this was not seen by the user yet
	protected long newTimestamp;

	// There might have been warnings
	protected StringBuffer warnings = new StringBuffer();

	// currently parsed
	private FileObject current;
	private String currentTag, body;

	public XMLFileReader(final FilesCollection plugins) {
		this.plugins = plugins;
	}

	public String getWarnings() {
		return warnings.toString();
	}

	public void read(final String updateSite)
		throws ParserConfigurationException, IOException, SAXException
	{
		final UpdateSite site = plugins.getUpdateSite(updateSite);
		if (site == null) throw new IOException("Unknown update site: " + site);
		final URL url = new URL(site.url + Util.XML_COMPRESSED);
		final URLConnection connection = url.openConnection();
		final long lastModified = connection.getLastModified();
		read(updateSite, new GZIPInputStream(connection.getInputStream()),
			site.timestamp);

		// lastModified is a Unix epoch, we need a timestamp
		site.timestamp = Long.parseLong(Util.timestamp(lastModified));
	}

	public void read(final File file) throws ParserConfigurationException,
		IOException, SAXException
	{
		read(null, new GZIPInputStream(new FileInputStream(file)), 0);
	}

	// timestamp is the timestamp (not the Unix epoch) we last saw updates from
	// this site
	public void read(final String updateSite, final InputStream in,
		final long timestamp) throws ParserConfigurationException, IOException,
		SAXException
	{
		this.updateSite = updateSite;
		newTimestamp = timestamp;

		final InputSource inputSource = new InputSource(in);
		final SAXParserFactory factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(true);

		// commented-out as per Postel's law
		// factory.setValidating(true);

		final SAXParser parser = factory.newSAXParser();
		final XMLReader xr = parser.getXMLReader();
		xr.setContentHandler(this);
		xr.setErrorHandler(new XMLFileErrorHandler());
		xr.parse(inputSource);
	}

	@Override
	public void startDocument() {
		body = "";
	}

	@Override
	public void endDocument() {}

	@Override
	public void startElement(final String uri, final String name,
		final String qName, final Attributes atts)
	{
		if ("".equals(uri)) currentTag = qName;
		else currentTag = name;

		if (currentTag.equals("plugin")) {
			String updateSite = this.updateSite;
			if (updateSite == null) {
				updateSite = atts.getValue("update-site");
				if (updateSite == null) updateSite =
					FilesCollection.DEFAULT_UPDATE_SITE;
			}
			current =
				new FileObject(updateSite, atts.getValue("filename"), null, 0,
					Status.NOT_INSTALLED);
			if (this.updateSite != null &&
				!this.updateSite.equals(FilesCollection.DEFAULT_UPDATE_SITE))
			{
				final FileObject already = plugins.getPlugin(current.filename);
				if (already != null && !this.updateSite.equals(already.updateSite)) warnings
					.append("Warning: '" + current.filename + "' from update site '" +
						this.updateSite + "' shadows the one from update site '" +
						already.updateSite + "'\n");
			}
			final String executable = atts.getValue("executable");
			if ("true".equalsIgnoreCase(executable)) current.executable = true;
		}
		else if (currentTag.equals("previous-version")) current.addPreviousVersion(
			atts.getValue("checksum"), getLong(atts, "timestamp"));
		else if (currentTag.equals("version")) {
			current.setVersion(atts.getValue("checksum"), getLong(atts, "timestamp"));
			current.filesize = getLong(atts, "filesize");
		}
		else if (currentTag.equals("dependency")) {
			// maybe sometime in the future final String timestamp =
			// atts.getValue("timestamp");
			final String overrides = atts.getValue("overrides");
			current.addDependency(atts.getValue("filename"), getLong(atts,
				"timestamp"), overrides != null && overrides.equals("true"));
		}
		else if (updateSite == null && currentTag.equals("update-site")) plugins
			.addUpdateSite(atts.getValue("name"), atts.getValue("url"), atts
				.getValue("ssh-host"), atts.getValue("upload-directory"), Long
				.parseLong(atts.getValue("timestamp")));
	}

	@Override
	public void
		endElement(final String uri, final String name, final String qName)
	{
		String tagName;
		if ("".equals(uri)) tagName = qName;
		else tagName = name;

		if (tagName.equals("description")) current.description = body;
		else if (tagName.equals("author")) current.addAuthor(body);
		else if (tagName.equals("platform")) current.addPlatform(body);
		else if (tagName.equals("category")) current.addCategory(body);
		else if (tagName.equals("link")) current.addLink(body);
		else if (tagName.equals("plugin")) {
			if (current.current == null) current
				.setStatus(Status.OBSOLETE_UNINSTALLED);
			else if (current.isNewerThan(newTimestamp)) {
				current.setStatus(Status.NEW);
				current.setAction(plugins, current.isUpdateablePlatform()
					? FileObject.Action.INSTALL : FileObject.Action.NEW);
			}
			final FileObject plugin = plugins.getPlugin(current.filename);
			if (updateSite == null && current.updateSite != null &&
				plugins.getUpdateSite(current.updateSite) == null) ; // ignore plugin
																															// with invalid
																															// update site
			else if (plugin == null) plugins.add(current);
			else {
				plugin.merge(current);
				if (updateSite != null &&
					(plugin.updateSite == null || !plugin.updateSite
						.equals(current.updateSite))) plugin.updateSite =
					current.updateSite;
			}
			current = null;
		}
		body = "";
	}

	@Override
	public void characters(final char ch[], final int start, final int length) {
		body += new String(ch, start, length);
	}

	private long getLong(final Attributes attributes, final String key) {
		final String value = attributes.getValue(key);
		return value == null ? 0 : Long.parseLong(value);
	}
}
