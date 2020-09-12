package org.mastodon.tomancak;

import sc.iview.SciView;
import graphics.scenery.Node;
import graphics.scenery.volumes.Volume;

import org.mastodon.app.ui.ViewMenuBuilder;
import org.mastodon.plugin.MastodonPlugin;
import org.mastodon.plugin.MastodonPluginAppModel;
import org.mastodon.model.HighlightModel;
import org.mastodon.model.FocusModel;
import org.mastodon.revised.mamut.KeyConfigContexts;
import org.mastodon.revised.mamut.MamutAppModel;
import org.mastodon.revised.mamut.MamutViewTrackScheme;
import org.mastodon.revised.model.mamut.Link;
import org.mastodon.revised.model.mamut.Spot;
import org.mastodon.revised.ui.coloring.GraphColorGenerator;
import org.mastodon.revised.ui.keymap.CommandDescriptionProvider;
import org.mastodon.revised.ui.keymap.CommandDescriptions;

import org.scijava.AbstractContextual;
import org.scijava.plugin.Plugin;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.RunnableAction;
import org.scijava.event.EventService;

import org.scijava.event.EventHandler;
import sc.iview.event.NodeActivatedEvent;
import sc.iview.vector.FloatVector3;

import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

import static org.mastodon.app.ui.ViewMenuBuilder.item;
import static org.mastodon.app.ui.ViewMenuBuilder.menu;

@Plugin( type = MastodonPlugin.class )
public class SciViewPlugin extends AbstractContextual implements MastodonPlugin
{
	private static final String SCIVIEW_CONNECTOR = "[tomancak] start SciView";
	private static final String[] SCIVIEW_CONNECTOR_KEYS = { "not mapped" };

	private static Map< String, String > menuTexts = new HashMap<>();
	static
	{
		menuTexts.put( SCIVIEW_CONNECTOR, "Start SciView" );
	}

	@Override
	public Map< String, String > getMenuTexts()
	{
		return menuTexts;
	}

	@Override
	public List< ViewMenuBuilder.MenuItem > getMenuItems()
	{
		return Arrays.asList(
				menu( "Plugins",
						item( SCIVIEW_CONNECTOR ) ) );
	}

	/** Command descriptions for all provided commands */
	@Plugin( type = CommandDescriptionProvider.class )
	public static class Descriptions extends CommandDescriptionProvider
	{
		public Descriptions()
		{
			super( KeyConfigContexts.TRACKSCHEME, KeyConfigContexts.BIGDATAVIEWER );
		}

		@Override
		public void getCommandDescriptions( final CommandDescriptions descriptions )
		{
			descriptions.add( SCIVIEW_CONNECTOR, SCIVIEW_CONNECTOR_KEYS, "Start SciView and a special Mastodon->SciView connector control panel." );
		}
	}


	private final AbstractNamedAction startSciViewConnectorAction;

	public SciViewPlugin()
	{
		startSciViewConnectorAction = new RunnableAction( SCIVIEW_CONNECTOR, this::startSciViewConnector );
		updateEnabledActions();
	}

	private MastodonPluginAppModel pluginAppModel;

	@Override
	public void setAppModel( final MastodonPluginAppModel model )
	{
		this.pluginAppModel = model;
		updateEnabledActions();
	}

	@Override
	public void installGlobalActions( final Actions actions )
	{
		actions.namedAction( startSciViewConnectorAction, SCIVIEW_CONNECTOR_KEYS );
	}

	private void updateEnabledActions()
	{
		final MamutAppModel appModel = ( pluginAppModel == null ) ? null : pluginAppModel.getAppModel();
		startSciViewConnectorAction.setEnabled( appModel != null );
	}


	private void startSciViewConnector()
	{
		new Thread("Mastodon's SciView")
		{
			@Override
			public void run()
			{
				final DisplayMastodonData dmd = new DisplayMastodonData(pluginAppModel);
				dmd.controllingBdvWindow.setupFrom(pluginAppModel);

				try {
					dmd.sv = SciView.create();
					dmd.sv.setInterpreterWindowVisibility(false);
					Thread.sleep(2000); //a bit of a grace time before we continue
					System.out.println("SciView started...");
				} catch (Exception e) {
					System.out.println("SciView has a problem:");
					e.printStackTrace();
					dmd.sv = null;
				}
				if (dmd.sv == null) return;

				//find the event service to be able to notify the inspector
				dmd.events = dmd.sv.getScijavaContext().getService(EventService.class);
				System.out.println("Found an event service: " + dmd.events);

				//show one volume
				//Volume v = dmd.showOneTimePoint(10);

				//show full volume
				Volume v = dmd.showTimeSeries();
				dmd.makeSciViewReadBdvSetting(v);
				DisplayMastodonData.showTransferFunctionDialog(getContext(),v);

				//show spots
				final Node spotsNode = new Node("Mastodon spots");
				dmd.centerNodeOnVolume(spotsNode,v); //so that shift+mouse rotates nicely
				dmd.sv.addNode(spotsNode);
				DisplayMastodonData.showSpotsDisplayParamsDialog(getContext(),spotsNode,dmd.spotVizuParams);

				//now, the spots are click-selectable in SciView and we want to listen if some spot was
				//selected/activated and consequently select it in the Mastodon itself
				dmd.events.subscribe(notifierOfMastodonWhenSpotIsSelectedInSciView);

				//show compass
				dmd.showCompassAxes(dmd, spotsNode.getPosition());

				if (dmd.controllingBdvWindow.isThereSome())
				{
					//setup coloring
					final MamutViewTrackScheme tsWin = pluginAppModel.getWindowManager().createTrackScheme();
					tsWin.getColoringModel().listeners().add( () -> {
						System.out.println("coloring changed");
						setColorGeneratorFrom(tsWin);
					});

					//setup highlighting
					sRef = pluginAppModel.getAppModel().getModel().getGraph().vertexRef();
					final HighlightModel<Spot, Link> highlighter = pluginAppModel.getAppModel().getHighlightModel();
					highlighter.listeners().add( () -> {
						if (highlighter.getHighlightedVertex(sRef) != null)
						{
							//System.out.println("focused on "+sRef.getLabel());
							updateFocus( dmd.sv.find(sRef.getLabel()) );
						}
						else
						{
							//System.out.println("defocused");
							updateFocus( null );
						}
					});

					//setup updating of spots to the currently viewed timepoint
					dmd.controllingBdvWindow.get()
							.getViewerPanelMamut()
							.addTimePointListener( tp -> {
								updateFocus(null);
								dmd.showSpots(tp,spotsNode,colorGenerator);
							} );

					//setup updating of spots when they are dragged in the BDV
					pluginAppModel.getAppModel()
						.getModel().getGraph()
						.addVertexPositionListener( l -> dmd.updateSpotPosition(spotsNode,l) );

					//setup "activating" of a Node in SciView in response
					//to focusing its counterpart Spot in Mastodon
					fRef = pluginAppModel.getAppModel().getModel().getGraph().vertexRef();
					final FocusModel<Spot, Link> focuser = pluginAppModel.getAppModel().getFocusModel();
					focuser.listeners().add(() -> {
						if (focuser.getFocusedVertex(fRef) != null)
						{
							final Node n = dmd.sv.find(fRef.getLabel());
							if (n != null) dmd.sv.setActiveCenteredNode(n);
						}
					});
				}
				else
				{
					//just show spots w/o any additional "services"
					dmd.showSpots( v.getCurrentTimepoint(), spotsNode);
				}

				//big black box
				FloatVector3 bbbP = new FloatVector3(spotsNode.getPosition().x,spotsNode.getPosition().y,spotsNode.getPosition().z );
				FloatVector3 bbbS = new FloatVector3(spotsNode.getPosition().x,spotsNode.getPosition().y,spotsNode.getPosition().z );
				bbbS.setX( bbbS.xf() *2);
				bbbS.setY( bbbS.yf() *2);
				bbbS.setZ( bbbS.zf() *2);
				final Node box = dmd.sv.addBox(bbbP,bbbS);
				box.setName("BLOCKING BOX");
				box.setVisible(false);

				dmd.sv.getFloor().setVisible(false);
				dmd.sv.centerOnNode(spotsNode);
			}

			//this object cannot be created within the scope of the run() method, otherwise
			//it will be GC'ed after run() is over.. and the listener will never get activated...
			final EventListener notifierOfMastodonWhenSpotIsSelectedInSciView = new EventListener();
			class EventListener extends AbstractContextual
			{
				@EventHandler
				public void onEvent(NodeActivatedEvent event) {
					if (event.getNode() == null) return;
					pluginAppModel.getAppModel().getModel().getGraph().vertices()
							.stream()
							.filter(s -> (s.getLabel().equals(event.getNode().getName())))
							.forEach(s -> pluginAppModel.getAppModel().getSelectionModel().setSelected(s,true));
				}
			}
		}.start();
	}

	//coloring attributes
	private GraphColorGenerator<Spot, Link> colorGenerator = null;
	private void setColorGeneratorFrom(final MamutViewTrackScheme tsWin)
	{
		colorGenerator = tsWin.getGraphColorGeneratorAdapter().getColorGenerator();
	}

	//focus attributes
	private Node stillFocusedNode = null;
	private Spot sRef,fRef;
	private void updateFocus(final Node newFocusNode)
	{
		/* DEBUG
		System.out.println("defocus: "+(stillFocusedNode != null ? stillFocusedNode.getName() : "NONE")
			+", focus newly: "+(newFocusNode != null ? newFocusNode.getName() : "NONE"));
		*/

		//something to defocus?
		if (stillFocusedNode != null && stillFocusedNode.getParent() != null)
		{
			stillFocusedNode.getScale().mul(0.66666f);
			stillFocusedNode.setNeedsUpdate(true);
		}

		//something to focus?
		stillFocusedNode = newFocusNode;
		if (stillFocusedNode != null)
		{
			stillFocusedNode.getScale().mul(1.50f);
			stillFocusedNode.setNeedsUpdate(true);
		}
	}
}
