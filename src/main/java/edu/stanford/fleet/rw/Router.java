/* 
 * Copyright (c) 2017 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *  
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
/**
 * 
 */
package edu.stanford.fleet.rw;

import java.util.*;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;


/**
 * Basic router for routing inter-site nets.
 * 
 * Created on: Aug 13, 2015
 */
public class Router extends AbstractRouter {

	private static HashSet<String> allLongLines;
	private static HashSet<String> intNodeQuadLongs;
	private static HashSet<String> allExclusiveIntSinks;
	private static HashMap<String,String> clkSitePIPNames;
	private static HashSet<String> lutOutputPinNames;
	
	private static boolean allowWireOverlap = false;
	
	public static boolean ENABLE_RIPUP = false; // TODO - This mode is WIP
	
	public static boolean ENABLE_LUT_INPUT_SWAP = true;

	
	private PBlock routingPblock;
	private Set<Tile> routingPblockTiles;
	
	private ArrayList<RouteNode> pathFromSinkToSwitchBox = null;
	private boolean isCurrNetClk;
	
	private ArrayList<SitePinInst> failedRoutes = new ArrayList<SitePinInst>();

	private boolean supressWarningsErrors = false;
	
	/** Nets found to conflict with a particular net that will be ripped-up and re-routed */
	private HashSet<RouteNode> conflictNodes;
	
	/** The additional min cost of adding a node to the queue when compared with the head */
	int minCeilingCost = 20;
	protected PriorityQueue<RouteNode> clockQueue;
	static {
		allLongLines = new HashSet<String>();
		for(int i=0; i < 4; i++){
			for(String endPoint : new String[]{"BEG", "END"}){
				allLongLines.add("NN12_"+endPoint+i);
				allLongLines.add("NN16_"+endPoint+i);
				allLongLines.add("SS12_"+endPoint+i);
				allLongLines.add("SS16_"+endPoint+i);
				
				allLongLines.add("WW12_"+endPoint+i);
				allLongLines.add("WW12_"+endPoint+(i+4));
				allLongLines.add("EE12_"+endPoint+i);
				allLongLines.add("EE12_"+endPoint+(i+4));
			}
		}
		
		//INT_NODE_QUAD_LONG_#_INT_OUT -> drives Long
		intNodeQuadLongs = new HashSet<String>();
		for(int i=0; i < 128; i++){
			intNodeQuadLongs.add("INT_NODE_QUAD_LONG_"+i+"_INT_OUT");
		}
		
		// INT SINKS
		allExclusiveIntSinks = new HashSet<String>();
		for(int i=0; i < 48; i++){
			allExclusiveIntSinks.add("IMUX_E" + i);
			allExclusiveIntSinks.add("IMUX_W" + i);

			if(i < 8){
				allExclusiveIntSinks.add("CTRL_E_B" + i);
				allExclusiveIntSinks.add("CTRL_W_B" + i);
			}
		}
		
		clkSitePIPNames = new HashMap<String, String>();
		// UltraScale
		clkSitePIPNames.put("CLK_B1", "CLK1INV");
		clkSitePIPNames.put("CLK_B2", "CLK2INV");
		clkSitePIPNames.put("LCLK_B", "LCLKINV");
		clkSitePIPNames.put("CLKAL_X", "CLK_OPTINV_CLKA_L");
		clkSitePIPNames.put("CLKAU_X", "CLK_OPTINV_CLKA_U");
		clkSitePIPNames.put("CLKBL_X", "CLK_OPTINV_CLKB_L");
		clkSitePIPNames.put("CLKBU_X", "CLK_OPTINV_CLKB_U");
		clkSitePIPNames.put("CLKFBIN", "");
		clkSitePIPNames.put("CLK_IN", "");
		clkSitePIPNames.put("REGCLKAL_X","REGCLK_OPTINV_CLKA_L");
		clkSitePIPNames.put("REGCLKAU_X","REGCLK_OPTINV_CLKA_U");
		clkSitePIPNames.put("REGCLKBL_X","REGCLK_OPTINV_CLKB_L");
		clkSitePIPNames.put("REGCLKBU_X","REGCLK_OPTINV_CLKB_U");
		clkSitePIPNames.put("CLK_B", "CLKINV");
		// Series 7
		clkSitePIPNames.put("CLK", "CLKINV");
		
		lutOutputPinNames = new HashSet<String>();
		for(String cle : new String[]{"L", "M"}){
			for(String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}){
				lutOutputPinNames.add("CLE_CLE_"+cle+"_SITE_0_"+pin+"_O");
			}
		}
	}

	
	public Router(Design design){
		super();
		this.design = design;
		dev = design.getDevice();
		clockQueue = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
	}
	
	public PBlock getRoutingPblock() {
		return routingPblock;
	}

	public void setRoutingPblock(PBlock routingPblock) {
		this.routingPblock = routingPblock;
		routingPblockTiles = new HashSet<>();
		for (PBlockRange r : routingPblock) {
			if ((r.getBottomLeftTile().getName().startsWith("BRAM") || r.getBottomLeftTile().getName().startsWith("DSP"))
				&& r.isSiteRange()) {
				int colMin = r.getBottomLeftTile().getColumn();
				int rowMin = r.getTopRightTile().getRow() - 4; // include all attached INTs
				int colMax = r.getTopRightTile().getColumn();
				int rowMax = r.getBottomLeftTile().getRow();

				// We may need to expand column to include outward facing CLB/DSP/BRAM to INT tiles
				Tile t = r.getLowerLeftSite().getIntTile();
				if(t.getColumn() < colMin) colMin = t.getColumn();
				t = r.getUpperRightSite().getIntTile();
				if(t.getColumn() > colMax) colMax = t.getColumn();

				for(int col=colMin; col <= colMax; col++){
					for(int row=rowMin; row <= rowMax; row++){
						routingPblockTiles.add(design.getDevice().getTile(row, col));
					}
				}
			} else {
				routingPblockTiles.addAll(r.getAllTiles());
			}
		}
	}

	private boolean canUseNode(RouteNode n){
		if(usedNodes.contains(n)){
			// Only allow over subscribed if the net is routed with this router
			//   We don't want to rip-up nets from pre-compiled blocks, these have
			//   already satisfied a timing constraint and should remain intact.
			return allowWireOverlap && usedNodesMap.get(n)!= null;
		}
		if(routingPblock != null){
			return routingPblockTiles.contains(n.getTile());
		}
		return true;
	}
	
	/**
	 * Prepares the class variables for the route() method. Sets everything up
	 * for each connection to be made. This method is called for each connection
	 * in a net by routeNet(). It calls route() once the variables are ready
	 * for routing.
	 * 
	 */
	protected void routeConnection(){
		prepareForRoutingConnection();
		
		// Do the actual routing
		route();
		totalNodesProcessed += nodesProcessed;
	}
	
	public RouteNode findSwitchBoxInput(RouteNode src){		
		RouteNode curr = src;
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		while(!isSwitchBox(curr.getTile())){
			if(curr.getConnections() != null){
				for(Wire conn : curr.getConnections()){
					q.add(new RouteNode(conn.getTile(),conn.getWireIndex(), curr, curr.getLevel()+1));
				}				
			}
			curr = q.remove();
		}
		
		return curr;
	}
	
	
	/**
	 * The heart of the router, it does the actual routing by consuming nodes on
	 * the priority queue and determining how to proceed to the sink. It is
	 * called by routeConnection().
	 */
	protected void route(){	
		int ceilingCost = (isCurrSinkAClkWire || currSinkPin.getSiteTypeEnum().equals(SiteTypeEnum.BUFGCTRL)) ? 2000 : minCeilingCost;
		
		// Iterate through all of the nodes in the queue, adding potential candidate nodes 
		// as we go along. We are finished when we find the sink node.
		boolean debug = false;
		
		while(!queue.isEmpty()){
			if(nodesProcessed > 100000){
				// If we haven't found a route by now, we probably never will
				return;
			}
			RouteNode currNode = queue.remove();
			if(debug) System.out.println(MessageGenerator.makeWhiteSpace(currNode.getLevel()) + currNode.toString() + " " + currNode.getIntentCode() + " *DQ*");
			nodesProcessed++;
			nextNode: for(Wire w : currNode.getConnections()){
				if(currNode.equals(currSink) || (w.getWireIndex() == this.currSink.getWire() && w.getTile().equals(currSink.getTile()))){
					
					// We've found the sink, lets retrace our steps
					RouteNode currPathNode = null;
					if(currNode.equals(currSink)){
						// The currNode itself is the sink
						currPathNode = currNode;
					}else{
						// The currNode's child wire is the sink
						currPathNode = new RouteNode(w.getTile(), w.getWireIndex(), currNode, currNode.getLevel()+1);
					}

					if(allowWireOverlap){
						conflictNodes = new HashSet<RouteNode>();
					}
					
					// Add this connection as a PIP, and follow it back to the source
					while(currPathNode.getParent() != null){
						if(allowWireOverlap){
							if(usedNodes.contains(currPathNode)){
								conflictNodes.add(currPathNode);
							}
						}
						for(Wire w1 : currPathNode.getParent().getTile().getWireConnections(currPathNode.getParent().getWire())){
							if(w1.getWireIndex() == currPathNode.getWire()){
								if(w1.isEndPIPWire() && currPathNode.getParent().getTile().equals(currPathNode.getTile())){
									PIP newPIP = new PIP(currPathNode.getTile(), currPathNode.getParent().getWire(), 
														 currPathNode.getWire());
									pipList.add(newPIP);
									break;
								}
							}
						}
						// Update the current node to the parent
						// this way we can traverse backwards to the source
						currPathNode = currPathNode.getParent();
					}
					// Include path (if any) from last switch box pin 
					if(pathFromSinkToSwitchBox != null && pathFromSinkToSwitchBox.size() > 1){
						RouteNode prev = null;
						for(RouteNode n : pathFromSinkToSwitchBox){
							if(prev != null && prev.getTile().equals(n.getTile())) {
								PIP newPIP = prev.getTile().getPIP(prev.getWire(), n.getWire());
								pipList.add(newPIP);
							}
							prev = n;
						}						
					}
					
					// We are now done with the routing of this connection
					successfulRoute = true;
					if(debug) {
						System.out.println("=========" + currNet.getName() + "::" + currSink.toString());
						for(PIP p : pipList) System.out.println(p.toString());
						System.out.println();
					}
					return;
				} 
				else{						
					// This is not the sink, but is this wire one we should look at in the future?
					Tile currTile = w.getTile();
					int currWire = w.getWireIndex();

					// Check if is a routethru, check if the site is consumed by something else
					// If a cell has been placed next to the first Site pin connection, we'll
					// assume this is not available
					if(currNode.getTile().equals(w.getTile()) && !currNode.getTile().getName().startsWith("INT")){
						// Look for possible route-thru conflict
						SitePin sp = currNode.getTile().getSitePinFromWire(currNode.getWire());
						// Check to make sure the site pin we found is not the source of the net
						if(sp != null && !sp.getSite().equals(currNet.getSource().getSite()) 
						        && !sp.getPinName().equals(currNet.getSource().getName())){
							for(BELPin p : sp.getBELPin().getSiteConns()){
								SiteInst si = design.getSiteInstFromSite(sp.getSite());
								if(si != null){
									if( si.getCell(p.getBEL().getName()) != null ) continue nextNode; 
								}
							}
						}
					}
					if(w.isRouteThru()){
						SitePin wsp = w.getSitePin();
						
						// TODO Let's not support LUT route-thrus for now
						if(wsp != null && Utils.isSLICE(wsp.getSite().getSiteTypeEnum())){
							continue nextNode;
						}
						SitePin pin = w.getSitePin();
						if(pin != null){
							SiteInst si = design.getSiteInstFromSite(pin.getSite());
							if(si != null){
								for(BELPin epin : pin.getBELPin().getSiteConns()){
									BEL et = epin.getBEL();
									if(et.getBELClass() == BELClass.RBEL){
										SitePIP sp = si.getUsedSitePIP(epin);
										if(sp != null){
											for(BELPin src : sp.getInputPin().getSiteConns()){
												if(!src.isOutput()) continue;
												Cell possibleCell = si.getCell(sp.getBELName());
												if(possibleCell != null) continue nextNode;
											}
										}
									}else{
										if(et != null && si.getCell(et.getName()) != null) continue nextNode;
									}
								}
							}							
						}
					}

					// Don't follow INT tile sinks 
					if(allExclusiveIntSinks.contains(currTile.getWireName(currWire)) && 
						switchMatrixSink != null && 
						!currTile.equals(switchMatrixSink.getTile())){
						continue;
					}
					
					RouteNode tmp = new RouteNode(currTile, currWire, currNode, currNode.getLevel()+1);
					// Check if this node has already been visited, if so don't add it
					if(!visitedNodes.contains(tmp) && canUseNode(tmp)){
						// Make sure we haven't used this node already
						if(tmp.getTile().getWireCount() > 0 && tmp.getConnections() != null){
							// This looks like a possible candidate for our next node, we'll add it
							setCost(tmp, w.isRouteThru());
							if(debug){ 
								System.out.println(MessageGenerator.makeWhiteSpace(currNode.getLevel()) 
										+ " -> " + tmp + " " + tmp.getIntentCode());
							}
							if(queue.isEmpty() || tmp.getCost() < (queue.peek().getCost() + ceilingCost)){
								visitedNodes.add(tmp);
								queue.add(tmp);
								if(currSources.contains(tmp)){
									tmp.setParent(null);
								}									
							}
						}
					} 
				}
			}
		}
	}
	

	
	private void prepareSwitchBoxSink(SitePinInst currPin){
		// For the input, find the entry point into its switch box
		switchMatrixSink = null;
		pathFromSinkToSwitchBox = findInputPinFeed(currPin); 
		if(pathFromSinkToSwitchBox != null){
			switchMatrixSink = pathFromSinkToSwitchBox.get(0);
			currSink = switchMatrixSink;
		}		
	}
	
	private void checkAndAddClockPinSitePIP(SitePinInst currSource, SitePinInst currPin){
		boolean currNetOutputFromBUF = currSource != null && currSource.isPinOnABuf();
		isCurrSinkAClkWire = (isClkPin(currSinkPin) || currSinkPin.getName().equals("C")) &&
							  (currNetOutputFromBUF || currSinkPin.isPinOnABuf()) && 
							  !currSource.getSiteTypeEnum().equals(SiteTypeEnum.CONFIG_SITE);
		if(isCurrSinkAClkWire){
			// Some clock pins need a site PIP to get fully routed
			String rBelName = clkSitePIPNames.get(currPin.getName());
			if(rBelName == null){
				if(!supressWarningsErrors) MessageGenerator.briefError("Warning unsupported clock pin: " + currPin);
			}else if(!rBelName.equals("")){
				SitePIP existingPIP = currPin.getSiteInst().getUsedSitePIP(rBelName);
				if(existingPIP == null) {
					//SitePIP p = new SitePIP(dev, currPin.getSiteInst(), rBelName, "CLK", "OUT");
					currPin.getSiteInst().addSitePIP(rBelName, "CLK");					
				}
			}
		}
	}
	
	/**
	 * Updates class members with the current route information
	 * @param currSource The source pin for this net
	 * @param currPin The current sink pin to be routed 
	 */
	public void prepareSinkPinsForRouting(SitePinInst currSource, SitePinInst currPin){
		// Set the appropriate variables
		prepareSink(currPin);
		
		// For the input, find the entry point into its switch box
		prepareSwitchBoxSink(currPin);
			
		// Some clock pins need a site PIP to get fully routed
		checkAndAddClockPinSitePIP(currSource,currPin);
	}
	
	/**
	 * Looks backwards from an input pin depth number of hops to see if there
	 * exists at least one free path.
	 * @param p Input pin to check routability
	 * @param depth Number of PIP hops to check
	 * @return True if there exists at least one path depth PIP hops free, false otherwise.
	 */
	public boolean isRoutable(RouteNode rn, int depth){
		if(usedNodes.contains(rn) || rn.getWireName().contains("LOGIC_OUT")){
			return false;
		}else if(rn.getLevel() == depth){
			return true;
		}
		RouteNode base = rn.getBaseWire();
		for(PIP pip : base.getBackwardPIPs()){
			RouteNode start = new RouteNode(base.getTile(),pip.getStartWireIndex());
			start.setLevel(rn.getLevel()+1);
			if(isRoutable(start, depth)) return true;
		}
		
		return false;
	}
	
	private static String[] lutIndices = new String[]{"1","2","3","4","5","6"}; 
	private static String[] lutBELSuffixes = new String[]{"5LUT", "6LUT"};
	
	public void swapLUTPinForUnused(SitePinInst p){
		String lutName = Character.toString(p.getName().charAt(0));
		String unusedLutPinIndex = null;
		for(String i : lutIndices){
			if(p.getSiteInst().getSitePinInst(lutName + i) == null){
				unusedLutPinIndex = i;
				break;
			}
		}
		if(unusedLutPinIndex != null){
			SiteInst i = p.getSiteInst();
			// All LUT BEL pin mappings (A5 and A6)
			for(String belName : lutBELSuffixes){
				Cell c = i.getCell(lutName + belName);
				if(c == null) continue;
				String logPin = c.removePinMapping("A" + p.getName().charAt(1));
				c.addPinMapping("A" + unusedLutPinIndex, logPin);
			}
			p.movePin(lutName + unusedLutPinIndex);
		}
	}
	
	/**
	 * Checks the current sink LUT to see if there are any alternative LUT input pins
	 * that could be used instead
	 * @return A list of available lut input pins that can be swapped
	 */
	public static List<String> getAlternativeLUTInputs(SitePinInst currSink){
		if(!currSink.isLUTInputPin()) return Collections.emptyList();
		Cell lut = DesignTools.getConnectedCells(currSink).iterator().next();
		String currLutType = lut.getBELName();
		String otherLutType = currLutType.endsWith("6LUT") ? currLutType.replace("6", "5") : currLutType.replace("5", "6");

		// If both LUT5 and LUT6 are occupied, let's not try to be fancy
		if(currSink.getSiteInst().getCell(otherLutType) != null) return Collections.emptyList();
		
		ArrayList<String> alternatives = new ArrayList<>();
		int size = currLutType.charAt(1) - 48;
		for(int i=size; i > 0; i--){
			String physName = "A" + i;
			String logPin = lut.getLogicalPinMapping(physName);
			if(logPin == null) alternatives.add(physName);
		}
		return alternatives;
	}
	
	/**
	 * Changes the physical pin mapping of lutInput to an alternate physical pin
	 * on a LUT in provide for an alternative routing solution.
	 * @param lutInput The physical pin on the site to be swapped
	 * @param newPinName The new physical BEL pin on the lut to serve as the new input.
	 */
	public static void swapLUTInputPins(SitePinInst lutInput, String newPinName){
		Cell lut = DesignTools.getConnectedCells(lutInput).iterator().next();
		String existingName = "A" + lutInput.getName().charAt(1);
		
		String logPin = lut.removePinMapping(existingName);
		lut.addPinMapping(newPinName, logPin);
		
		lutInput.movePin(Character.toString(lutInput.getName().charAt(0)) + newPinName.charAt(1));
	}
	
	
	/**
	 * This method routes all the connections within a net.  
	 */
	public void routeNet(){	
		SitePinInst currSource = currNet.getSource();
		currSources = new HashSet<RouteNode>();
		boolean firstSinkToRouteInNet = true;
				
		// Check for LUT inputs that will need to be swapped
		ArrayList<SitePinInst> pinsToSwap = null;
		for(SitePinInst currPin : currNet.getPins()){
			if(!currPin.isLUTInputPin()) continue;
			int wire = currPin.getSiteInst().getSite().getTileWireIndexFromPinName(currPin.getName());
			RouteNode rn = new RouteNode(currPin.getTile(),wire);
			if(!isRoutable(rn, 2)){
				if(pinsToSwap == null) pinsToSwap = new ArrayList<SitePinInst>();
				pinsToSwap.add(currPin);
				System.out.println(" WILL ATTEMPT TO SWAP LUT INPUT: " + currPin.getNet().getName() + " " + currPin.getName());
			}
		}
		if(pinsToSwap != null){
			for(SitePinInst curr : pinsToSwap){
				swapLUTPinForUnused(curr);
			}
		}
		
		// Route each pin by itself
		for(SitePinInst currPin : currNet.getPins()){
			// Ignore the source pin
			if (currPin.isOutPin()) continue; 

			prepareSinkPinsForRouting(currSource, currPin);
			
			if(firstSinkToRouteInNet){
				// just add the original source
				addInitialSourceForRouting(currSource);
			}
			else{
				// Leverage previous routings to offer additional starting points for this route 
				getSourcesFromPIPs(pipList, currSources/* TODO - prune this list to make it faster*/);
			}
			
			// Route the current sink node
			totalConnections++;
			routeConnection();
			
			
			// If initial route fails, see if we can swap a LUT input
			if(!successfulRoute){
				String origPinName = "A" + currSinkPin.getName().charAt(1);
				for(String alternate : getAlternativeLUTInputs(currSinkPin)){
					swapLUTInputPins(currSinkPin, alternate);
					prepareSinkPinsForRouting(currSource, currSinkPin);
					routeConnection();
					if(successfulRoute) break;
				}
				if(!successfulRoute) {
					// If we couldn't route by swapping, return pin to original location
					swapLUTInputPins(currSinkPin, origPinName);
				}
			}
			

			// Check if it was a successful routing
			if(successfulRoute){
				// Add these PIPs to the rest used in the net
				netPIPs.addAll(pipList);
				currPin.setRouted(true);
			} 
			else{
				if(ENABLE_RIPUP){
					failedRoutes.add(currPin);					
				}else{
					failedConnections++;
					String switchMatrixString = switchMatrixSink != null ? switchMatrixSink.getTile().getName() + " " + switchMatrixSink.getWireName() : "null";
					if(!supressWarningsErrors){ 
						MessageGenerator.briefError("\tFAILED TO ROUTE: net: " +
							currNet.getName() + " inpin: " + currSinkPin.getName() +
							" (" + currSink.getTile().getName() + " "+  currSink.getWireName() +
							" / "+switchMatrixString+") on instance: " + currSinkPin.getSiteInstName());
					}
				}
			}
			firstSinkToRouteInNet = false;
		}
	}
	
	public boolean isSupressWarningsErrors() {
		return supressWarningsErrors;
	}

	public void setSupressWarningsErrors(boolean supressWarningsErrors) {
		this.supressWarningsErrors = supressWarningsErrors;
	}

	/**
     * Creates sources from a list of PIPs
	 * @param pips The pips of the net to examine.
	 * @return The list of sources gathered from the pips list.
	 */
	public HashSet<RouteNode> getSourcesFromPIPs(List<PIP> pips, HashSet<RouteNode> sources){
		if(isCurrNetClk){
			for(PIP pip : pips){
				if(isSwitchBox(pip.getTile())){
					if(!allExclusiveIntSinks.contains(pip.getStartWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getStartWireIndex(), null, 0));
					if(!allExclusiveIntSinks.contains(pip.getEndWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getEndWireIndex(), null, 0));
				}
			}
		}else{
			for(PIP pip : pips){
				if(isSwitchBox(pip.getTile())){
					if(!allExclusiveIntSinks.contains(pip.getStartWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getStartWireIndex(), null, 0));
					if(!allExclusiveIntSinks.contains(pip.getEndWireName()))
						sources.add(new RouteNode(pip.getTile(), pip.getEndWireIndex(), null, 0));
				}
			}			
		}
		return sources;
	}
	
	public static boolean isSwitchBox(Tile t){
		TileTypeEnum tt = t.getTileTypeEnum();
		if(t.getDevice().getSeries() == Series.Series7){
			return tt == TileTypeEnum.INT_L || tt == TileTypeEnum.INT_R;
		}
		return tt == TileTypeEnum.INT;
	}
	
	/**
	 * Certain input pins in a switch box can also serve as a bounce.  We need to
	 * prevent the usage of the bounce if the pin will be need to route an input pin.
	 * This method reserves those wires by marking them used.  They are later released
	 * as the routing for that particular net is starting.
	 */
	public void reserveCriticalNodes(){
		for(Net n : design.getNets()){
			if(n.getPins().size() == 0) continue;
			if(n.isStaticNet()){
				// TODO - Right now, we are just un-routing the entire GND/VDD nets
				// - it might be better to leverage parts of it
				n.unroute();
			}
			ArrayList<RouteNode> routeNodes = new ArrayList<RouteNode>();
			for(SitePinInst p : n.getPins()){
				if(p.isRouted()) continue;
				if(p.isOutPin()) continue;
				ArrayList<RouteNode> reserveMe = findInputPinFeed(p);
				if(reserveMe == null) continue;
				routeNodes.add(reserveMe.get(0));
				markNodeUsed(reserveMe.get(0));
			}
			if(routeNodes.size() > 0) reservedNodes.put(n, routeNodes);
		}
	}
	
	public void reserveCriticalNodes(ArrayList<SitePinInst> sitePinInsts){
		nextPin: for(SitePinInst p : sitePinInsts){
			ArrayList<RouteNode> routeNodes = new ArrayList<RouteNode>();
			ArrayList<RouteNode> reserveMe = findInputPinFeed(p);
			if(reserveMe == null) continue;
			for(RouteNode rn : reserveMe){
				if(usedNodes.contains(rn)){
					System.err.println("WARNING: Unable to reserve node " + rn + 
						" for net "+p.getNet().getName()+" as it is already in use." + 
						" This could lead to an unroutable situation.");
					continue nextPin;
				}
			}
			routeNodes.add(reserveMe.get(0));
			markNodeUsed(reserveMe.get(0));
			ArrayList<RouteNode> existingReserved = reservedNodes.get(p.getNet());
			if(existingReserved != null){
				routeNodes.addAll(existingReserved);
			}
			reservedNodes.put(p.getNet(), routeNodes);
		}
	}
	
	/**
	 * This router will preserve all existing routes (even partials) intact.
	 * This method will mark them as used to avoid route conflicts.
	 */
	public void markExistingRouteResourcesUsed(){
		for(Net n : design.getNets()){
			if(!n.hasPIPs()) continue; 
			for(PIP p : n.getPIPs()){
				markNodeUsed(new RouteNode(p.getTile(),p.getStartWireIndex()));
				markNodeUsed(new RouteNode(p.getTile(),p.getEndWireIndex()));
			}
		}
	}
	
	public void identifyMissingPins(){
		// Let's just look at GND/VCC for CTAGs
		// Also, reserve site output pins that are tagged GLOBAL_LOGIC*
		//   This is to avoid conflicts and safeguard internal site nets where the LUT
		//   is supplying VCC/GND to the CARRY BEL for example 
		for(SiteInst i : design.getSiteInsts()){
			for(Entry<String,Net> e : i.getNetSiteWireMap().entrySet()){
				Net n = e.getValue();
				if(e.getKey().equals(Net.GND_WIRE_NAME)) continue;
				if(n.getType() == NetType.GND || n.getType() == NetType.VCC){
					if(i.getSitePinInst(e.getKey()) == null && i.getSite().hasPin(e.getKey())){
						if(i.getSite().isOutputPin(e.getKey())){
							// Reserve this node for future route
							int idx = i.getSite().getTileWireIndexFromPinName(e.getKey());
							RouteNode reserveMe = new RouteNode(i.getTile(),idx);
							ArrayList<RouteNode> currReserved = reservedNodes.get(n);
							if(currReserved == null){
								currReserved = new ArrayList<RouteNode>();
								reservedNodes.put(n, currReserved);
							}
							currReserved.add(reserveMe);
							markNodeUsed(reserveMe);
						}else{
							// Add a new sink to the GLOBAL_LOGIC* net
							n.addPin(new SitePinInst(false, e.getKey(), i));
						}
						
					}
				}
			}
		}
	}
	
	public void markAndUpdateNetPIPsAsUsed(){
		// Mark these used PIPs as used in the data structures
		for (PIP pip : netPIPs){
			setWireAsUsed(pip.getTile(), pip.getStartWireIndex(), currNet);
			setWireAsUsed(pip.getTile(), pip.getEndWireIndex(), currNet);
			markIntermediateNodesAsUsed(pip, currNet);
		}
		// Let's add these PIPs to the actual net, to be included in the design
		currNet.setPIPs(netPIPs);
	}
	
	/**
	 * This method handles queries during the static source routing process. 
	 * It determines if the node in question can be used as a source for the current
	 * NetType.
	 * @param n The node in question
	 * @param type The NetType to indicate what kind of static source we need (GND/VCC)
	 * @return True if the pin is a hard source or an unused LUT output that can be repurposed as a source
	 */
	private boolean isNodeUsableStaticSource(RouteNode n, NetType type){
		// We should look for 3 different potential sources
		// before we stop:
		// (1) GND_WIRE 
		// (2) VCC_WIRE 
		// (3) Unused LUT Outputs (A_0, B_0,...,H_0)
		String pinName = type == NetType.VCC ? Net.VCC_WIRE_NAME : Net.GND_WIRE_NAME;
		if(n.getWireName().startsWith(pinName)){
			return true;
		}else if(lutOutputPinNames.contains(n.getWireName())){
			// If lut is unused, we can re-purpose it for a static source
			Site slice = n.getTile().getSites()[0];
			SiteInst i = design.getSiteInstFromSite(slice);			
			if(i == null) return true; // Site is not used
			char uniqueId = n.getWireName().charAt(n.getWireName().length()-3);
			Net currNet = i.getNetFromSiteWire(uniqueId + "_O");
			if(currNet == null) return true;
			if(currNet.getType() == type) return true;
			return false;
			/*String proposedLutName = uniqueId + "6LUT";
			for(Cell c : i.getCells()){
				if(proposedLutName.equals(c.getBel().getName())){
					return false;
				}
			}
			return true;*/
		}
		return false;
	}
	
	/**
	 * Determines if the given node can serve as our sink and updates the net PIPs respectively
	 * if they can be used.
	 * @param n RouteNode in question
	 * @param type The net type to designate the static source type
	 * @return true if this sources is useable and updates the netPIPs accordingly, false otherwise. 
	 */
	private boolean isThisOurStaticSource(RouteNode n, NetType type, boolean debug){
		boolean usable = isNodeUsableStaticSource(n, type);
		if(!usable) return false;
		RouteNode currPathNode = n;
		// Add this connection as a PIP, and follow it back to the source
		while(currPathNode.getParent() != null){
			for(Wire w : currPathNode.getConnections()){
				if(w.getWireIndex() == currPathNode.getParent().getWire() && w.isEndPIPWire()){
					PIP p = new PIP(currPathNode.getTile(),currPathNode.getWire(),currPathNode.getParent().getWire(),w.getPIPType());
					if(debug) {
						System.out.println("  " + p.toString());
					}
					netPIPs.add(p);
					break;
				}
			}
			currPathNode = currPathNode.getParent();
		}
		return true;
	}
	
	public RouteNode getRAMSink(RouteNode sink){
		for(Wire w : sink.getConnections()){
			if (w.getTile().getName().contains("BRAM")){
				RouteNode outSink = new RouteNode(w.getTile(),w.getWireIndex(), sink, sink.getLevel()+1);
				currSink = outSink;
				return outSink;
			}
		}
		return null;
	}
	
				
	public Wire checkSink(RouteNode myNode){
		for(Wire w : myNode.getConnections()){
			if (w.getTile().equals(currSink.getTile())&&w.getWireIndex()==currSink.getWire()){
				return w;
			}
		}
		return null;
	}
	
	public void routeStaticNet(){
		NetType netType = currNet.getType();
		// Assume the net is completely un-routed 
		// For each pin, route backward from the input pin
		for(SitePinInst sink : currNet.getPins()){
			boolean debug = false;
			if(sink.isOutPin()) continue;
			int watchdog = 10000;
			int wire = sink.getSiteInst().getSite().getTileWireIndexFromPinName(sink.getName());
			
			if(wire == -1) {
				throw new RuntimeException("ERROR: Problem while trying to route static sink " + sink);
			}
			Tile t = sink.getTile();
			if(debug) {
				System.out.println("SINK: " + t.getName() + " " + t.getWireName(wire));
			}
			
			Node node = new Node(t,wire);
			RouteNode n = new RouteNode(node.getTile(),node.getWire());
			Queue<RouteNode> q = new LinkedList<RouteNode>();
			visitedNodes = new HashSet<RouteNode>();
			q.add(n);
			boolean success = false;
			while(!q.isEmpty()){
				n = q.poll();
				visitedNodes.add(n);
				if(debug) System.out.println("DEQUEUE:" + n);
				if(success = isThisOurStaticSource(n, netType, debug)) break;
				for(Wire w : n.getBackwardConnections()){
					if(w.isRouteThru()) continue;
					RouteNode nParent = new RouteNode(w.getTile(),w.getWireIndex(), n, n.getLevel()+1);
					if(!pruneNode(nParent)) q.add(nParent);
				}
				watchdog--;
				if(watchdog < 0) {
					break;
				}
			}
			if(!success){
				System.out.println("FAILED to route " + netType + " pin " + sink.toString());
			}else{
				sink.setRouted(true);
			}
			
			currNet.setPIPs(netPIPs);
		}
		
	}
	
	
	private boolean pruneNode(RouteNode routeNode){
		switch (routeNode.getIntentCode()){
			case NODE_GLOBAL_VDISTR:
			case NODE_GLOBAL_HROUTE:
			case NODE_GLOBAL_HDISTR:
			case NODE_HLONG:
			case NODE_VLONG:
			case NODE_GLOBAL_VROUTE:
			case NODE_GLOBAL_LEAF:
			case NODE_GLOBAL_BUFG:
				return true;
			default:
		}
		if(usedNodes.contains(routeNode)) return true;
		if(visitedNodes.contains(routeNode)) return true;
		return false;
	}
	
	public void routeStaticNets(){
		for(String staticNetName : new String[]{Net.GND_NET, Net.VCC_NET} ){
			Net staticNet = design.getNet(staticNetName); 
			if(staticNet != null && staticNet.getPIPs().size() == 0){
				currNet = staticNet;
				// release some reservedNodes
				ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
				if(rNodes != null){
					usedNodes.removeAll(rNodes);
				}
				netPIPs = new HashSet<PIP>(currNet.getPIPs());
				routeStaticNet();	
				markAndUpdateNetPIPsAsUsed();
			}			
		}		
	}

	public Cell getBestMatchingCell(String expectedName, String expectedPortName) {
		Design d = getDesign();
		if (d.getCell(expectedName) != null) {
			return d.getCell(expectedName);
		}
		for (Cell c : d.getCells()) {
			if (c.getName().startsWith(expectedName) && c.getCorrespondingSitePinName(expectedPortName) != null) {
				return c;
			}
		}
		return null;
	}
	
	/**
	 * Assumes design is fully placed and that all site nets are routed
	 * but that not all physical nets ({@link Net}) or physical pins 
	 * ({@link SitePinInst}) have been created. TODO - Experimental stage
	 */
	public void elaboratePhysicalNets() {
		Design d = getDesign();
		EDIFNetlist n = d.getNetlist();
		d.getNetlist().resetParentNetMap();
		Map<String,String> parentNetMap = getDesign().getNetlist().getParentNetMap();
		
		// Build a reverse net (Parent Net -> Net Aliases)
		Map<String,HashSet<String>> reverseNetMap = new HashMap<>();
		for(Entry<String,String> e : parentNetMap.entrySet()){
			HashSet<String> aliases = reverseNetMap.get(e.getValue());
			if(aliases == null) {
				aliases = new HashSet<>();
				reverseNetMap.put(e.getValue(), aliases);
			}
			aliases.add(e.getKey());
		}
		
		// For each aliased set of nets, find all primitive cell pins and ensure
		// SitePinInsts exist on the net
		for(Entry<String,HashSet<String>> e : reverseNetMap.entrySet()) {
			Net parentNet = d.getNet(e.getKey());
			if(parentNet == null) {
				continue;
				// EDIFHierNet logicalNet = n.getHierNetFromName(e.getKey());
				// parentNet = new Net(logicalNet);
				// parentNet = d.createNet(logicalNet.getNet());
			}
			for(String alias : e.getValue()) {
				EDIFHierNet aliasNet = n.getHierNetFromName(alias);
				if(aliasNet == null) {
					continue; // TODO - handle transformed prims
				}
				for(EDIFPortInst p : aliasNet.getNet().getPortInsts()) {
					if(p.getCellInst() == null) continue; // Top-level/hier port
					if(p.getCellInst().getCellType().isPrimitive()) {
						// Create/ensure SitePinInst 
						String cellName = aliasNet.getHierarchicalInstName(p);
						Cell c = getBestMatchingCell(cellName, p.getName());
						if(c == null) {
							System.out.println("Couldn't find a matching cell for " + cellName + "...");
							continue; // TODO - Figure out why...
						}
						String sitePinName = c.getCorrespondingSitePinName(p.getName());
						if(sitePinName == null) {
							System.out.println("Couldn't find site pin for " + p.getName() + " in cell " + c + "...");
							continue; //TODO - failed to figure out site pin
						}
						SitePinInst spi = c.getSiteInst().getSitePinInst(sitePinName);
						if(spi == null) {
							spi = parentNet.createPin(p.isOutput(), sitePinName, c.getSiteInst());
						}
					}
				}
			}
			
		}
		
	}
	
	
	/**
	 * This the central method for routing the design in this class.  This prepares
	 * the nets for routing.
	 * @return The final routed design.
	 */
	public Design routeDesign(){
		identifyMissingPins();
		reserveCriticalNodes();
		markExistingRouteResourcesUsed();
		
		// Start Routing
		for (Net nn : design.getNets()){
			currNet = nn;

			// Ignore nets with no pins
			if(currNet.getPins().size() == 0){
				continue;
			}
			
			// Consider all nets as fully routed except static nets (TODO - add support to analyze all nets)
			if(currNet.getPIPs().size() > 0) continue;

			if (currNet.isClockNet()) {
				System.out.println("Skipping clock net " + currNet + "...");
				continue;
			}

			/*
			if (currNet.isStaticNet()) {
				System.out.println("Skipping static net " + currNet + "...");
				continue;
			}
			*/

			System.out.println("Routing " + currNet + "...");
		
			if(currNet.getSource() == null && !currNet.isStaticNet()){
				EDIFNet logNet = currNet.getLogicalNet();
				if(logNet != null && logNet.getParentCell().getName().equals("IOBUF")) {
					continue;
				}
				if(!supressWarningsErrors) MessageGenerator.briefError("WARNING: " + currNet.getName() + " does not have a source pin associated with it.");
				continue;
			}
			
			// release some reservedNodes
			ArrayList<RouteNode> rNodes = reservedNodes.remove(currNet);
			
			if(rNodes != null){
				usedNodes.removeAll(rNodes);
			}
			
			// netPIPs are the pips that belong to a particular net
			netPIPs = new HashSet<PIP>(currNet.getPIPs());
			long start = System.currentTimeMillis();
			if(currNet.isStaticNet()){
				routeStaticNet();
			}else{
				routeNet();
			}
			
			if(netPIPs.size() == 0 && rNodes != null){
				usedNodes.addAll(rNodes);
				reservedNodes.put(nn, rNodes);
			}
			
			long stop = System.currentTimeMillis();
			//if(stop-start > 100) System.out.println((stop-start) + "ms : " + currNet.getName());
			
			markAndUpdateNetPIPsAsUsed();
		}

		return design;
	}	
	
	protected static void printTimeHelper(String timedOperation, long start) {
		System.out.printf("%s %8.3fs\n", timedOperation,
				(System.nanoTime() - start) / 1000000000.0);
	}
	
	private static HashSet<String> ignoreInputs;
	
	static {
		ignoreInputs = new HashSet<String>();
		ignoreInputs.add("CIN");
		for(int i=0; i < 16; i++){
			ignoreInputs.add("CLK_IN" + i);
			ignoreInputs.add("CE_INT" + i);
		}
	}
	
	/**
	 * Attempts to find a switch box wire that will drive the site pin provided.
	 * @param p The pin to start from
	 * @return A wire (tile and wire) of a switch box that can drive the site pin, or null
	 * if none could be found.
	 */
	public static ArrayList<RouteNode> findInputPinFeed(SitePinInst p){
		Site site = p.getSiteInst().getSite();
		String pinName = p.getName();
		Tile t = site.getTile();
		if(site.isOutputPin(pinName)) return null;
		if(ignoreInputs.contains(pinName)) return null;
		int watchdog = 1000;
		int wire = site.getTileWireIndexFromPinName(pinName);
		if(wire == -1) return null; // Pin is not connected to anything (unbonded IO)
		Node node = new Node(t,wire);
		RouteNode n = new RouteNode(node.getTile(),node.getWire());
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		q.add(n);
		while(!q.isEmpty()){
			n = q.poll(); 
			if(isSwitchBox(n.getTile())){
				ArrayList<RouteNode> path = new ArrayList<RouteNode>();
				while(n != null){
					path.add(n);
					n = n.getParent();
				}
				return path;
			}
			for(PIP pip : n.getBackwardPIPs()){
				Wire tmp = new Wire(n.getTile(),pip.getStartWireIndex());
				RouteNode newNode = new RouteNode(tmp.getTile(),tmp.getWireIndex(),n,n.getLevel()+1); 
				q.add(newNode);
				Wire nodeHead = tmp.getStartWire();
				if(!nodeHead.equals(tmp)){
					q.add(new RouteNode(nodeHead.getTile(),nodeHead.getWireIndex(),newNode,newNode.getLevel()+1));
				}
				
			}
			watchdog--;
			if(watchdog < 0) break;
		}
		return null;
	}
	
	public static void main(String[] args) {
		if(args.length != 2){
			System.out.println("USAGE: <input.dcp> <output.dcp>");
		}
		CodePerfTracker t = new CodePerfTracker("Router", true);
		Router r = new Router(Design.readCheckpoint(args[0],t));
		t.start("Route Design");
		r.routeDesign();
		t.stop();
		r.getDesign().writeCheckpoint(args[1],t);
	}
}
