/*******************************************************************************
 * Copyright (c) 2016 Pablo Pavon Mariño.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 ******************************************************************************/




 





package com.net2plan.examples.general.reports;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.net2plan.interfaces.networkDesign.Demand;
import com.net2plan.interfaces.networkDesign.IReport;
import com.net2plan.interfaces.networkDesign.Link;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.interfaces.networkDesign.NetworkLayer;
import com.net2plan.interfaces.networkDesign.Node;
import com.net2plan.interfaces.networkDesign.ProtectionSegment;
import com.net2plan.interfaces.networkDesign.Route;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.libraries.TrafficComputationEngine;
import com.net2plan.libraries.WDMUtils;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Triple;

/**
 *
 * <p>This report collects information about the Routing and Spectrum assignment in the network, as well as other general information about the WDM layer.</p>
 * <p>This report is valid for the WDM layers compatible with the {@link com.net2plan.libraries.WDMUtils WDMUtils} library .This includes both fixed and flexi-grid networks, 
 *  with unique or mixed line rates in the lightpaths, with or without optical signal regenerators and wavelength (frequency slot) conversions.</p>
 * <p>The report first checks that the WDM network follows the conventions described in {@link com.net2plan.libraries.WDMUtils WDMUtils} library (see its Javadoc for further information on this).</p>
 * <p>Then, the report provides a number of statistics regarding frequency slot occupation, optical signal 
 *  regenerators, wavelength (frequency slot) converters needed, etc. It also warns about possible frequency slot clashing 
 *  two lightpaths using the same slot in the same fibers)</p>
 *  <p>Lightpaths are separated into:</p>
 *  <ul>
 *  <li>Regular lightpaths: Those stored as {@code Route} objects in the design.</li>
 *  <li>Protection lightpaths: Those stored as {@code ProtectionSegment} objects in the design.</li>
 *  </ul>
 * @net2plan.keywords WDM
 * @author Pablo Pavon-Marino
 */ 
public class Report_wdm_routingSpectrumAndModulationAssignments implements IReport
{
	/* Input parameters */
	private NetPlan netPlan;
	private NetworkLayer originalDefaultLayer;
	private Map<String, String> reportParameters;
	private Statistics stat;
	private InputParameter wdmLayerIndex = new InputParameter ("wdmLayerIndex", (int) 0 , "Index of the WDM layer (-1 means default layer)");

	@Override
	public String executeReport(NetPlan netPlan, Map<String, String> reportParameters, Map<String, String> net2planParameters)
	{
		/* Initialize all InputParameter objects defined in this object (this uses Java reflection) */
		InputParameter.initializeAllInputParameterFieldsOfObject(this, reportParameters);

		/* Input parameters */
		this.netPlan = netPlan;
		this.reportParameters = reportParameters;
		this.originalDefaultLayer = netPlan.getNetworkLayerDefault();
		final NetworkLayer wdmLayer = wdmLayerIndex.getInt () == -1? netPlan.getNetworkLayerDefault() : netPlan.getNetworkLayer(wdmLayerIndex.getInt ());
		this.netPlan.setNetworkLayerDefault(wdmLayer);

		Map<Link, LinkedList<String>> warnings_e = new LinkedHashMap<Link, LinkedList<String>>();

		String res = printReport();
		netPlan.setNetworkLayerDefault(originalDefaultLayer);
		return res;
	}

	@Override
	public String getDescription()
	{
		return "This report shows line engineering information for WDM links in the network. Further description in the HTML generated.";
	}

	@Override
	public List<Triple<String, String, String>> getParameters()
	{
		/* Returns the parameter information for all the InputParameter objects defined in this object (uses Java reflection) */
		return InputParameter.getInformationAllInputParameterFieldsOfObject(this);
	}

	@Override
	public String getTitle()
	{
		return "WDM line engineering";
	}

	private String printReport()
	{
		StringBuilder out = new StringBuilder();
		DecimalFormat df_2 = new DecimalFormat("###.##");

		out.append("<html><body>");
		out.append("<head><title>WDM Lightpath Routing and Spectrum Assignment (fixed or flexi-grid networks) report</title></head>");
		out.append("<h1>WDM Lightpath Routing and Spectrum Assignment report (fixed or flexi-grid networks)</h1>");
		out.append("<p>This report collects information about the Routing and Spectrum assignment in the network, as well as other general information about the WDM layer.</p>");
		out.append("<p>This report is valid for the WDM layers compatible with the WDMUtils library .This includes both fixed and flexi-grid networks, "
				+ "with unique or mixed line rates in the lightpaths, with or without optical signal regenerators and wavelength (frequency slot) conversions.</p>");
		out.append("<p>The report first checks that the WDM network follows the conventions described in WDMUtils library (see its Javadoc for further information on this).</p>");
		out.append("<p>Then, the report provides a number of statistics regarding frequency slot occupation, optical signal "
				+ "regenerators, wavelength (frequency slot) converters needed, etc. It also warns about possible frequency slot clashing "
				+ "(two lightpaths using the same slot in the same fibers)</p>");
		out.append("<p>Lightpaths are separated into:</p>");
		out.append("<ul>");
		out.append("<li>Regular lightpaths: Those stored as Route objects in the design.</li>");
		out.append("<li>Protection lightpaths: Those stored as ProtectionSegment objects in the design.</li>");
		out.append("</ul>");
		out.append("<h2>Click to go to...</h2>");
		out.append("<ul>");
		out.append("<li><a href=\"#inputParameters\">Input parameters</a></li>");
		out.append("<li><a href=\"#generalStats\">General statistics</a></li>");
		out.append("<li><a href=\"#linkStats\">Per fiber statistics (including slot occupation map)</a></li>");
		out.append("<li><a href=\"#routeStats\">Per regular lightpath statistics</a></li>");
		out.append("<li><a href=\"#protectionStats\">Per protection lightpath statistics</a></li>");
		out.append("<li><a href=\"#nodeStats\">Per OADM statistics</a></li>");
		out.append("</ul>");

		
		out.append("<h2><a name=\"inputParameters\"></a>Input Parameters</h2>");
		out.append("<table border='1'>");
		out.append("<tr><th><b>Name</b></th><th><b>Value</b></th><th><b>Description</b></th>");

		for (Triple<String, String, String> paramDef : getParameters())
		{
			String name = paramDef.getFirst();
			String description = paramDef.getThird();
			String value = reportParameters.get(name);
			out.append("<tr><td>").append(name).append("</td><td>").append(value).append("</td><td>").append(description).append("</td></tr>");
		}
		out.append("</table>");

		/* Check that the topology is well formed */
		out.append("<h2><a name=\"malformedMessages\"></a>MALFORMED WDM LAYER WARNINGS</h2>");
		out.append("<p>This section gets possible format errors in the WDM layer attributes of links, routes, protection segments, according to the "
				+ "WDMUtils library conventions. Resource allocation clashings are not checked here. Any failure should be solved before this report can show any information</p>");
		boolean correctFormat = true;
		out.append("<table border='1'>");
		out.append("<tr><th align=\"left\"><b>Format errors</b></th></tr>");
		for (Link e : netPlan.getLinks()) if (!WDMUtils.isWDMFormatCorrect(e)) { correctFormat = false ; out.append("<tr><td>Fiber " + e.getIndex() + ": incorrect format</td></tr>"); }
		for (Route r : netPlan.getRoutes()) if (!WDMUtils.isWDMFormatCorrect(r)) { correctFormat = false ; out.append("<tr><td>Route " + r.getIndex() + ": incorrect format</td></tr>"); }
		for (ProtectionSegment r : netPlan.getProtectionSegments()) if (!WDMUtils.isWDMFormatCorrect(r)) { correctFormat = false ; out.append("<tr><td>ProtectionSegment " + r.getIndex() + ": incorrect format</td></tr>"); }
		if (correctFormat) out.append("<tr><td bgcolor=\"PaleGreen\">No format errors!!!</td></tr>"); 
		out.append("</table>");
		if (!correctFormat) return out.toString();

		this.stat = new Statistics(netPlan,netPlan.getNetworkLayerDefault());
		out.append("<h2><a name=\"generalStats\"></a>GENERAL STATISTICS - Signal metrics at the input of end OADM</h2>");
		out.append("<table border='1'>");
		out.append("<tr><th align=\"left\"><b>OADM stats</b></th></tr>");
		out.append("<tr><td align=\"left\">Number of OADMs</td><td>" + netPlan.getNumberOfNodes() + "</td></tr>");
		out.append("<tr><td align=\"left\">Node in degree (min/average/max)</td><td>" + stat.nodeInDegree.toString(df_2) + "</td></tr>");
		out.append("<tr><td align=\"left\">Node out degree (min/average/max)</td><td>" + stat.nodeOutDegree.toString(df_2) + "</td></tr>");
		
		out.append("<tr><th align=\"left\"><b>Fiber link stats</b></td></tr>");
		out.append("<tr><td align=\"left\">Number of fibers</td><td>" + netPlan.getNumberOfLinks() + "</td></tr>");
		out.append("<tr><td align=\"left\">Number of frequency slots per fiber (min/average/max)</td><td>" + stat.numberFrequencySlotsPerLink + "</td></tr>");
		out.append("<tr><td align=\"left\">Number of slots occupied per fiber (min/average/max)</td><td>" + stat.linkUtilizationIncludingProtSegments.toString(df_2) + "</td></tr>");
		out.append("<tr><td align=\"left\">The topology of fibers is bidirectional (in fibers and number of slots)?</td><td>" + stat.bidirectionalLinks + "</td></tr>");
		
		out.append("<tr><th align=\"left\"><b>Traffic stats</b></td></tr>");
		out.append("<tr><td align=\"left\">Number of demands</td><td>" + netPlan.getNumberOfDemands() + "</td></tr>");
		final double offeredTraffic = netPlan.getVectorDemandOfferedTraffic().zSum();
		final double blockedTraffic = netPlan.getVectorDemandBlockedTraffic().zSum();
		out.append("<tr><td align=\"left\">Total offered traffic</td><td>" + df_2.format(offeredTraffic) + "</td></tr>");
		out.append("<tr><td align=\"left\">Total blocked traffic</td><td>" + df_2.format(blockedTraffic) + "(block prob: " + df_2.format(offeredTraffic == 0? 0 : blockedTraffic / offeredTraffic) + ")" + "</td></tr>");
		out.append("<tr><td align=\"left\">The offered traffic is symmetric?</td><td>" + stat.bidirectionalDemands + "</td></tr>");

		out.append("<tr><th align=\"left\"><b>Lightpath stats</b></td></tr>");
		out.append("<tr><td align=\"left\">Number of lightpaths (route objects)</td><td>" + netPlan.getNumberOfRoutes() + "</td></tr>");
		out.append("<tr><td align=\"left\">The lightpaths are bidirectional? (same number of the same line rate between each node pair)?</td><td>" + stat.bidirectionalRoutes + "</td></tr>");
		out.append("<tr><td align=\"left\">Some demands are carried by more than one lightpath?</td><td>" + stat.unicastRoutingBifurcated + "</td></tr>");
		out.append("<tr><td align=\"left\">Lightpath length in km (min/average/max)</td><td>" + stat.lpLengthKm.toString(df_2) + "</td></tr>");
		out.append("<tr><td align=\"left\">Lightpath length in num hops (min/average/max)</td><td>" + stat.lpLengthHops.toString(df_2) + "</td></tr>");
		out.append("<tr><td align=\"left\">Lightpath propagation delay in ms (min/average/max)</td><td>" + stat.lpLengthMs.toString(df_2) + "</td></tr>");
		out.append("<tr><td align=\"left\">Total number of signal regenerators placed</td><td>" + stat.numberOfSignalRegenerators + "</td></tr>");
		out.append("<tr><td align=\"left\">Total number of frequency slot converters needed</td><td>" + stat.numberOfWavelengthConversions + "</td></tr>");
		
		out.append("<tr><th align=\"left\"><b>Resilience stats</b></th></tr>");
		out.append("<tr><td align=\"left\">Number of reserved protection segments</td><td>" + netPlan.getNumberOfProtectionSegments() + "</td></tr>");
		out.append("<tr><td align=\"left\">The protection lightpaths are bidirectional? (same number of the same number of slots reserved between each node pair)?</td><td>" + stat.bidirectionalProtectionSegments + "</td></tr>");
		out.append("<tr><td align=\"left\">Fiber capacity (number of slots) reserved for protection (min/average/max)</td><td>" + stat.fiberCapacityReservedForProtection.toString(df_2) + "</td></tr>");
		final Triple<Double, Double, Double> resilienceInfo = TrafficComputationEngine.getTrafficProtectionDegree(netPlan);
		out.append("<tr><td align=\"left\">% of carried traffic unprotected</td><td>" + df_2.format(resilienceInfo.getFirst()) + " %" + "</td></tr>");
		out.append("<tr><td align=\"left\">% of carried traffic with complete and dedicated protection (e.g. 1+1)</td><td>" + df_2.format(resilienceInfo.getSecond()) + " %" + "</td></tr>");
		out.append("<tr><td align=\"left\">% of carried traffic with partial and/or shared protection</td><td>" + df_2.format(resilienceInfo.getThird()) + " %" + "</td></tr>");
		out.append("</table>");
		
		/* Per link information */
		out.append("<h2><a name=\"linkStats\"></a>PER FIBER INFORMATION SUMMARY</h2>");
		out.append("<p>This table shows information for each fiber. In particular, the slots occupied, with a link to the lightpaths occupying it, either for regular lightpaths (L), or lightpaths defined as protection segments (P) that reserve slots:</p>");
		out.append("<ul>");
		out.append("<li>Black: The slot number is higher than the capacity declared for the link, and is not assigned to any lightpath.</li>");
		out.append("<li>White: The slot is within the fiber capacity, and is not assigned to any lightpath.</li>");
		out.append("<li>Green: The slot is within the fiber capacity, and is occupied by one regular lightpath and assigned to no protection lightpath.</li>");
		out.append("<li>Yellow: The slot is within the fiber capacity, and is occupied by zero regular lightpaths and assigned to one protection lightpath.</li>");
		out.append("<li>Red: The slot is within the fiber capacity, and is occupied by more than one lightpath (summing regular and protection), or is outside the link capacity and is assigned to at leastone lightpath.</li>");
		out.append("</ul>");
		out.append("<table border='1'>");
		out.append("<tr><th><b>Fiber #</b></th><th><b>Origin node</b></th><th><b>Dest. node</b></th><th><b>% slots used</b></th><th><b>Ok?</b></th>");
		for (int s = 0; s < stat.maxNumberSlots ; s ++) out.append("<th>" + s + "</th>");
		out.append("</tr>");
		
		for (Link e : netPlan.getLinks())
		{
			final int numSlotsThisFiber = WDMUtils.getFiberNumFrequencySlots(e);
			out.append("<tr>");
			out.append("<td><a name=\"fiber" + e.getIndex() + "\">" + e.getIndex() + " (id: " + e.getId() + ")"+ "</a></td>");
			out.append("<td>" + printNode (e.getOriginNode()) + "</td>");
			out.append("<td>" + printNode (e.getDestinationNode()) + "</td>");
			int numSlotsUsedThisFiber = 0; 
			boolean everythingOk = true;
			StringBuffer thisLine = new StringBuffer ();
			for (int s = 0; s < stat.maxNumberSlots ; s ++)
			{
				Pair<List<Route> , List<ProtectionSegment>> lists = stat.slotOccupInfo.get(Pair.of(e,s)); 
				List<Route> lps = lists == null? null : lists.getFirst();
				List<ProtectionSegment> lpProts = lists == null? null : lists.getSecond();
				String color = "";
				final boolean inFiberCapacity = (s < numSlotsThisFiber); 
				final int numLps = lps == null? 0 : lps.size();
				final int numProts = lpProts == null? 0 : lpProts.size();
				numSlotsUsedThisFiber += numLps + numProts > 0? 1 : 0;
				if (!inFiberCapacity && (numLps + numProts == 0)) color = "black";
				else if (!inFiberCapacity && (numLps + numProts > 0)) { color = "red"; everythingOk = false; }
				else if (inFiberCapacity && (numLps + numProts == 0)) color = "white";
				else if (inFiberCapacity && (numLps == 1) && (numProts == 0)) color = "PaleGreen";
				else if (inFiberCapacity && (numLps == 0) && (numProts == 1)) color = "yellow";
				else { color = "red"; everythingOk = false; }
				thisLine.append("<td bgcolor=\"" + color + "\">");
				if (lps != null) for (Route r : lps) thisLine.append("<a href=\"#lp" + r.getIndex() + "\">L" + r.getIndex() + " </a>");
				if (lpProts != null) for (ProtectionSegment segment : lpProts) thisLine.append("<a href=\"#lpProt" + segment.getIndex() + "\">P" + segment.getIndex() + " </a>");
				thisLine.append("</td>");
			}
			out.append("<td>" + ((double) numSlotsUsedThisFiber) / ((double) numSlotsThisFiber) + "</td>");
			out.append("<td bfcolor=\"" + (everythingOk? "PaleGreen" : "red")   +"\">" + (everythingOk? "Yes" : "No") +  "</td>");
			out.append(thisLine.toString());
			out.append("</tr>");
		}
		out.append("</table>");

		/* Per Route lightpath information */
		out.append("<h2><a name=\"routeStats\"></a>PER LIGHTPATH INFORMATION SUMMARY</h2>");
		out.append("<p>This table shows information for each regular lightpath: lightpaths defined as Route objects in the design (in opposition to lightpaths defined as ProtectionSegments that reserve a slots in the links).</p>");
		out.append("<table border='1'>");
		out.append("<tr><th><b>Lighpath Route #</b></th><th><b>Demand #</b></th><th><b>Origin node</b></th>"
				+ "<th><b>Dest. node</b></th><th><b>Trav. nodes</b></th><th><b>Length (km)</b></th><th><b>Propagation delay (ms)</b></th>"
				+ "<th><b>Line rate (Gbps)</b></th><th><b>Num. slots</b></th><th><b>Occupied slots</b></th>"
				+ "<th><b>Wavelength conversion?</b></th><th><b>Wavelength contiguity?</b></th></th>"
				+ "<th><b>Num. regenerators (reg. nodes)</b></th><b>Protection segments assigned</b></th></tr><th><b>Ok?</b></th></tr>");
		for (Route r : netPlan.getRoutes())
		{
			WDMUtils.RSA rsa = new WDMUtils.RSA(r , false);
			out.append("<tr>");
			out.append("<td><a name=\"lp" + r.getIndex() + "\">" + r.getIndex() + " (id: " + r.getId() + ")"+ "</a></td>");
			out.append("<td>" + r.getDemand().getIndex() + "</td>");
			out.append("<td>" + printNode(r.getIngressNode())+ "</td>");
			out.append("<td>" + printNode(r.getEgressNode())+ "</td>");
			out.append("<td>" + seqNodesString(r.getSeqLinksRealPath()) + "</td>");
			out.append("<td>" + df_2.format(r.getLengthInKm()) + "</td>");
			out.append("<td>" + df_2.format(r.getPropagationDelayInMiliseconds()) + "</td>");
			out.append("<td>" + df_2.format(r.getCarriedTraffic()) + "</td>");
			out.append("<td>" + rsa.getNumSlots() + "</td>");
			out.append("<td>" + occupiedSlotsString(rsa) + "</td>");
			out.append("<td>" + rsa.hasFrequencySlotConversions() + "</td>");
			out.append("<td>" + rsa.isFrequencySlotContiguous () + "</td>");
			List<Node> regPoints = rsa.getSignalRegenerationNodes (); 
			String regNodesString = ""; for (Node n : regPoints) regNodesString += "(n" + n.getIndex() + ", " + n.getName() + ") ";
			out.append("<td>" + regPoints.size() + (regPoints.isEmpty()? "" : "[ " + regNodesString + "]"));
			out.append("</td>");
			out.append("<td>");
			for (ProtectionSegment segment : r.getPotentialBackupProtectionSegments())
				out.append("<a href=\"#lpProt" + segment.getIndex() + "\">P" + segment.getIndex() + "</a> ");
			out.append("</td>");
			boolean isOk = isOk(rsa);
			out.append("<td bgcolor=\""  +  (isOk? "PaleGreen" : "red") +"\">" + (isOk? "Yes" : "No") + "</td>");
			out.append("</tr>");
		}
		out.append("</table>");

		/* Per protection lightpath information */
		out.append("<h2><a name=\"protectionStats\"></a>PER PROTECTION LIGHTPATH INFORMATION SUMMARY</h2>");
		out.append("<p>This table shows information for each so-called protection lightpath: lightpaths defined as ProtectionSegment objects in the design that reserve slots in the links.</p>");
		out.append("<table border='1'>");
		out.append("<tr><th><b>Lighpath ProtectionSegment #</b></th><th><b>Primary routes #</b></th><th><b>Origin node</b></th>"
				+ "<th><b>Dest. node</b></th><th><b>Trav. nodes</b></th><th><b>Length (km)</b></th><th><b>Propagation delay (ms)</b></th>"
				+ "<th><b>Num. slots</b></th><th><b>Occupied slots</b></th>"
				+ "<th><b>Wavelength conversion?</b></th><th><b>Wavelength contiguity?</b></th></th>"
				+ "<th><b>Num. regenerators (reg. nodes)</b></th><th><b>Ok?</b></th></tr>");
		for (ProtectionSegment segment : netPlan.getProtectionSegments ())
		{
			WDMUtils.RSA rsa = new WDMUtils.RSA(segment);
			out.append("<tr>");
			out.append("<td><a name=\"lpProt" + segment.getIndex() + "\">" + segment.getIndex() + " (id: " + segment.getId() + ")"+ "</a></td>");
			out.append("<td>");
			for (Route r : segment.getAssociatedRoutesToWhichIsBackup())
				out.append("<a href=\"#lp" + r.getIndex() + "\">L" + r.getIndex() + "</a> ");
			out.append("</td>");
			out.append("<td>" + printNode(segment.getOriginNode()) + "</td>");
			out.append("<td>" + printNode(segment.getDestinationNode()) + "</td>");
			out.append("<td>" + seqNodesString(segment.getSeqLinks()) + "</td>");
			out.append("<td>" + df_2.format(segment.getLengthInKm()) + "</td>");
			out.append("<td>" + df_2.format(segment.getPropagationDelayInMs()) + "</td>");
			out.append("<td>" + rsa.getNumSlots() + "</td>");
			out.append("<td>" + occupiedSlotsString(rsa) + "</td>");
			out.append("<td>" + rsa.hasFrequencySlotConversions() + "</td>");
			out.append("<td>" + rsa.isFrequencySlotContiguous () + "</td>");
			List<Node> regPoints = rsa.getSignalRegenerationNodes (); 
			
			out.append("<td>" + regPoints.size() + (regPoints.isEmpty()? "" : "(" + regPoints + ")"));
			out.append("</td>");
			boolean isOk = isOk(rsa);
			out.append("<td bgcolor=\""  +  (isOk? "PaleGreen" : "red") +"\">" + (isOk? "Yes" : "No") + "</td>");
			out.append("</tr>");
		}
		out.append("</table>");

		
		/* Per OADM information */
		out.append("<h2><a name=\"nodeStats\"></a>PER OADM NODE INFORMATION SUMMARY</h2>");
		out.append("<p>This table shows information for each Optical Add/Drop Multiplexer (OADM) node in the network.</p>");
		out.append("<table border='1'>");
		out.append("<tr><th><b>OADM # and name</b></th><th><b>Num. input fibers</b></th><th><b>Num. output fibers</b></th>"
				+ "<th><b>Num. add lps total (reg/prot)</b></th><th><b>Num. drop lps total (reg/prot)</b></th><th><b>Num. express lps total (reg/prto)</b></th>"
				+ "<th><b>Num. signal regenerators total (reg/prot)</b></th>"
				+ "<th><b>Num. slot conversions total (reg/prot)</b></th></tr>");
		for (Node n : netPlan.getNodes())
		{
			final int addRegLps = n.getOutgoingRoutes().size();
			final int dropRegLps = n.getIncomingRoutes().size();
			final int expressRegLps = n.getAssociatedRoutes().size() - addRegLps - dropRegLps;
			int addProtLps = 0; int dropProtLps = 0; int expressProtLps = 0;
			for (ProtectionSegment s : n.getAssociatedProtectionSegments())
				if (s.getOriginNode() == n) addProtLps ++; else if (s.getDestinationNode() == n) dropProtLps ++; else expressProtLps ++;
			Pair<List<Route>,List<ProtectionSegment>> lists = stat.regOccupInfo.get(n);
			final int regenRegLp = lists == null? 0 : lists.getFirst().size();
			final int regenProtLp = lists == null? 0 : lists.getSecond().size();
			int wcRegLp = 0; for (Route r : n.getAssociatedRoutes()) for (Node nReg : stat.routeLpRSA.get(r.getIndex()).getNodesWithFrequencySlotConversion()) if (n == nReg) wcRegLp ++;
			int wcProtLp = 0; for (ProtectionSegment r : n.getAssociatedProtectionSegments()) for (Node nReg : stat.protLpRSA.get(r.getIndex()).getNodesWithFrequencySlotConversion()) if (n == nReg) wcProtLp ++;
			out.append("<tr>");
			out.append("<td><a name=\"node" + n.getIndex() + "\">n" + n.getIndex() + " (" + n.getName() + ")" + "</a></td>");
			out.append("<td>" + n.getIncomingLinks().size() + "</td>");
			out.append("<td>" + n.getOutgoingLinks().size() + "</td>");
			out.append("<td>" + (addRegLps+addProtLps) + "(" + addRegLps + " / " + addProtLps + ")" + "</td>");
			out.append("<td>" + (dropRegLps+dropProtLps) + "(" + dropRegLps + " / " + dropProtLps + ")" + "</td>");
			out.append("<td>" + (expressRegLps+expressProtLps) + "(" + expressRegLps + " / " + expressProtLps + ")" + "</td>");
			out.append("<td>" + (regenProtLp+regenRegLp) + "(" + regenRegLp + " / " + regenProtLp + ")" + "</td>");
			out.append("<td>" + (wcRegLp+wcProtLp) + "(" + wcRegLp + " / " + wcProtLp + ")" + "</td>");
		}
		out.append("</table>");

		
		out.append("</body></html>");
		return out.toString();
	}


	private boolean isOk (WDMUtils.RSA rsa)
	{
		int counterLink = 0;
		for (Link e : rsa.seqLinks)
		{
			for (int contS = 0; contS < rsa.getNumSlots() ; contS ++)
			{
				final int s = rsa.seqFrequencySlots_se.get(contS , counterLink);
				if (s >= WDMUtils.getFiberNumFrequencySlots(e)) return false;
				final int numLps = stat.slotOccupInfo.containsKey(Pair.of(e,s))? stat.slotOccupInfo.get(Pair.of(e,s)).getFirst().size() : 0;
				final int numLpsProt = stat.slotOccupInfo.containsKey(Pair.of(e,s))? stat.slotOccupInfo.get(Pair.of(e,s)).getSecond().size() : 0;
				if (numLps+numLpsProt > 1) return false;
			}
			counterLink ++;
		}
		return true;
	}
	
	private String occupiedSlotsString (WDMUtils.RSA rsa)
	{
		if (!rsa.hasFrequencySlotConversions() && rsa.isFrequencySlotContiguous ()) return rsa.seqFrequencySlots_se.get(0,0) + "-"  +rsa.seqFrequencySlots_se.get(rsa.getNumSlots()-1,0);
		String res = "";
		for (int e = 0 ; e < rsa.seqFrequencySlots_se.rows() ; e ++)
		{
			res += "<p>";
			for (int s = 0 ; s < rsa.getNumSlots() ; s ++) res += rsa.seqFrequencySlots_se.get(e,s) + " ";
			res += "</p>";
		}
		return rsa.toString();
	}
	
	private static class Statistics
	{
		private MinMaxAvCollector nodeInDegree = new MinMaxAvCollector ();
		private MinMaxAvCollector nodeOutDegree = new MinMaxAvCollector ();
		private int numberOfSignalRegenerators = 0;
		private int numberOfWavelengthConversions = 0;
		private int maxNumberSlots;
		private boolean bidirectionalLinks;
		private boolean bidirectionalDemands;
		private boolean bidirectionalRoutes;
		private boolean bidirectionalProtectionSegments;
		private MinMaxAvCollector numberFrequencySlotsPerLink = new MinMaxAvCollector ();
		private boolean unicastRoutingBifurcated;
		private MinMaxAvCollector linkUtilizationIncludingProtSegments = new MinMaxAvCollector ();
		private MinMaxAvCollector lpLengthKm = new MinMaxAvCollector ();
		private MinMaxAvCollector lpLengthHops = new MinMaxAvCollector ();
		private MinMaxAvCollector lpLengthMs = new MinMaxAvCollector ();
		private MinMaxAvCollector fiberCapacityReservedForProtection = new MinMaxAvCollector ();
		private Map<Pair<Link,Integer>,Pair<List<Route>,List<ProtectionSegment>>> slotOccupInfo = null;
		private Map<Node,Pair<List<Route>,List<ProtectionSegment>>> regOccupInfo = null;
		private List<WDMUtils.RSA> routeLpRSA = new ArrayList<WDMUtils.RSA> ();
		private List<WDMUtils.RSA> protLpRSA = new ArrayList<WDMUtils.RSA> ();
		
		
		private Statistics (NetPlan netPlan , NetworkLayer wdmLayer) 
		{ 
			for (Node n : netPlan.getNodes())
			{
				nodeInDegree.add(n.getIncomingLinks(wdmLayer).size());
				nodeOutDegree.add(n.getOutgoingLinks(wdmLayer).size());
			}
			bidirectionalLinks = GraphUtils.isWeightedBidirectional(netPlan.getNodes() , netPlan.getLinks() , netPlan.getVectorLinkCapacity());
			bidirectionalDemands = GraphUtils.isWeightedBidirectional(netPlan.getNodes() , netPlan.getDemands() , netPlan.getVectorDemandOfferedTraffic());
			bidirectionalRoutes = GraphUtils.isWeightedBidirectional(netPlan.getNodes() , netPlan.getRoutes () , netPlan.getVectorRouteCarriedTraffic());
			bidirectionalProtectionSegments = GraphUtils.isWeightedBidirectional(netPlan.getNodes() , netPlan.getProtectionSegments() , netPlan.getVectorProtectionSegmentOccupiedCapacity());
			for (Link e : netPlan.getLinks())
			{
				numberFrequencySlotsPerLink.add((double) WDMUtils.getFiberNumFrequencySlots(e));
				linkUtilizationIncludingProtSegments.add(e.getOccupiedCapacityIncludingProtectionSegments());
				fiberCapacityReservedForProtection.add(e.getOccupiedCapacityIncludingProtectionSegments() - e.getOccupiedCapacityNotIncludingProtectionSegments());
			}
			unicastRoutingBifurcated = false;
			for (Demand d : netPlan.getDemands())
				if (d.getRoutes().size() > 1) { unicastRoutingBifurcated = false; break; }
			for (Route r : netPlan.getRoutes())
			{
				lpLengthHops.add(r.getNumberOfHops());
				lpLengthKm.add(r.getLengthInKm());
				lpLengthMs.add(r.getPropagationDelayInMiliseconds());
			}
			this.maxNumberSlots = (int) WDMUtils.getVectorFiberNumFrequencySlots(netPlan).getMaxLocation() [0];
			Pair<Map<Pair<Link,Integer>,Pair<List<Route>,List<ProtectionSegment>>> , Map<Node,Pair<List<Route>,List<ProtectionSegment>>>> pair = WDMUtils.getNetworkSlotOccupancyMap(netPlan , false);
			this.slotOccupInfo = pair.getFirst();
			this.regOccupInfo = pair.getSecond();
			for (Node n : netPlan.getNodes())
			{
				Pair<List<Route>,List<ProtectionSegment>> lists = regOccupInfo.get(n); 
				numberOfSignalRegenerators += (lists == null? 0 : lists.getFirst().size() + lists.getSecond().size());
			}	
					
			for (Route lp : netPlan.getRoutes())
			{
				WDMUtils.RSA rsa = new WDMUtils.RSA(lp , false);
				numberOfWavelengthConversions += rsa.getNodesWithFrequencySlotConversion().size();
				routeLpRSA.add(rsa);
			}
			for (ProtectionSegment lp : netPlan.getProtectionSegments())
			{
				WDMUtils.RSA rsa = new WDMUtils.RSA(lp);
				numberOfWavelengthConversions += rsa.getNodesWithFrequencySlotConversion().size();
				protLpRSA.add(rsa);
			}
		}
		
	}

	private String seqNodesString(List<Link> seqLinks)
	{
		if (seqLinks.isEmpty()) return "";
		String st = "[ " + printNode(seqLinks.get(0).getOriginNode());
		for (Link e : seqLinks)
			st += " -> " + printNode(e.getDestinationNode());
		return st + " ]";
	}

	private String printNode (Node n) { return "<a href=\"#node" + n.getIndex() + "\">n" + n.getIndex() + " (" + n.getName() + ")</a>"; }
	
	private static class MinMaxAvCollector
	{
		private double min, max, accum;
		private int numSamples;
		MinMaxAvCollector () { min = 0; max = 0; accum = 0; numSamples = 0; }
		void add (double sample) { min = numSamples == 0? sample : Math.min(min,sample); max = numSamples == 0? sample : Math.max(max,sample); accum += sample; numSamples ++; }
		double getAv () { return numSamples == 0? 0 : accum/numSamples;  }
		public String toString () { return min + " / " + getAv() + " / " + max; } 
		public String toString (DecimalFormat df) { return df.format(min) + " / " + df.format(getAv()) + " / " + df.format(max); } 
	}
}
