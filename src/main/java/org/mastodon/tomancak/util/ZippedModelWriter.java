package org.mastodon.tomancak.util;

import org.mastodon.project.MamutProject;
import org.mastodon.project.WriteZip;

import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Path;

public class ZippedModelWriter implements MamutProject.ProjectWriter
{
	/** the main output source */
	private WriteZip zip;

	public ZippedModelWriter(final Path filename) throws IOException
	{
		zip = new WriteZip(filename.toFile());
	}

	@Override
	public OutputStream getRawModelOutputStream() throws IOException
	{
		return zip.getOutputStream("model.raw");
	}

	@Override
	public OutputStream getRawTagsOutputStream() throws IOException
	{
		return zip.getOutputStream("tags.raw");
	}

	@Override
	public void close() throws IOException
	{
		zip.close();
	}


	/** fake output stream that saves nothing */
	private OutputStream nullOutput = new OutputStream() {
		@Override
		public void write(int i)
		{ /* does nothing, intentionally */ }
	};

	// -------- methods that offer "no-save" streams --------
	@Override
	public OutputStream getProjectXmlOutputStream()
	{
		return nullOutput;
	}

	@Override
	public OutputStream getFeatureOutputStream(String featureKey)
	{
		return nullOutput;
	}
}
