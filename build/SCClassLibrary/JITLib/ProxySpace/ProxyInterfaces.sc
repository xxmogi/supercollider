

//lightweight objects that insulate different ways of playing/stopping.
//the bundle that is passed in is a MixedBundle
//these could be players in future, a superclass of AbstractPlayer would be required

AbstractPlayControl {
	var <source, <>channelOffset;
	
	*new { arg source, channelOffset=0;
		^super.newCopyArgs(source, channelOffset);
	}
	
	sendDef {}
	writeDef {}
	prepareForPlay {}
	build { ^true }
	pause { this.stop } 
	unpause { this.start }
	synth { ^nil }
	
	readyForPlay { ^true }
	synthDef { ^nil }

	sendDefToBundle {}
	spawnToBundle {}
	
	playToBundle { arg bundle, args; 
		bundle.addAction(this, \play); //no latency (latency is in stream already)
		^nil //return a nil object instead of a synth
	}
	
	freeToBundle { arg bundle;
		bundle.addAction(this, \free);
	}
	
	stopToBundle { arg bundle;
		bundle.addAction(this, \stop);
	}
	
	stopClientToBundle { arg bundle;
		//used in shared node proxy
		this.stopToBundle(bundle);
	}
	
	play {
		this.subclassResponsibility(thisMethod);
	}
	
	stop {
		this.subclassResponsibility(thisMethod);
	}
	
	free {}
	
}

StreamControl : AbstractPlayControl {
	var <stream, clock;
		
	play { arg proxy;
		stream.stop;
		stream.play(clock, false); //(clock);
	}
	
	stop {
		stream.stop;
	}
	
	free { stream.stop; stream = nil }
	
	playToBundle { arg bundle, args; 
		if(stream.isPlaying.not, {
			bundle.addAction(this, \play); //no latency (latency is in stream already)
		})
		^nil //return a nil object instead of a synth
	}
	
	mute {
		stream.mute;
	}
	unmute {
		stream.unmute;
	}
	
	build { arg proxy;
		stream = source.buildForProxy(proxy, channelOffset);
		clock = proxy.clock;
		^stream.notNil;
	}
	readyForPlay { ^stream.notNil }
	clear {
		stream = nil;
	}
	
}

SynthControl : AbstractPlayControl {
	var <synth, >canReleaseSynth=true, >canFreeSynth=true;
	
	sendDef { } //assumes that SoundDef does send to the same server 
	writeDef { }
	build { ^true }
	sendDefToBundle {}
	name { ^source }
	clear { synth = nil }
	
	spawnToBundle { arg bundle, extraArgs, target, addAction=0; //assumes self freeing
		var synthMsg;
		synthMsg = [9, this.name, -1, addAction, target.asTarget.nodeID]++extraArgs; 
		bundle.add(synthMsg);
	}
	
	playToBundle { arg bundle, extraArgs, target, addAction=\addToTail;
		synth = Synth.newToBundle(bundle, this.name, extraArgs, target.asTarget, addAction);
		synth.isPlaying = true;
		^synth
	}
	
	
	stopToBundle { arg bundle;
		if(synth.isPlaying, {
			if(this.canReleaseSynth, {
					bundle.add([15, synth.nodeID, \gate, 0.0]); //to be sure.
			}, {
					if(this.canFreeSynth.not, { bundle.add([11, synth.nodeID]) }); //"/n_free"
			});
			synth.isPlaying = false;
		});
		

	}
	
	freeToBundle { arg bundle;
		this.stopToBundle(bundle);
	}

	play { arg group, extraArgs;
		var bundle;
		bundle = MixedBundle.new;
		this.playToBundle(bundle, extraArgs, group);
		bundle.send(group.server)
		^synth
	}
	stop { arg latency;
		var bundle;
		bundle = List.new;
		this.stopToBundle(bundle);
		synth.server.listSendBundle(latency, bundle)
	} 
			
	stopClientToBundle { }
	
	pause { synth.run(false) }
	unpause { synth.run(true) }
	canReleaseSynth { ^canReleaseSynth }
}


SynthDefControl : SynthControl {
	classvar <>writeDefs=false;
	var <synthDef;
	
	readyForPlay { ^synthDef.notNil }
	canReleaseSynth { ^synthDef.canReleaseSynth }
	canFreeSynth { ^synthDef.canFreeSynth }
	
	build { arg proxy, index=0; 
		var ok;
		synthDef = source.buildForProxy(proxy, channelOffset, index);//channelOffest
		ok = proxy.initBus(synthDef.rate, synthDef.numChannels);
		if(ok && synthDef.notNil, { 
			//schedule to avoid hd sleep latency. def writing is only for server reboot
			if(writeDefs, {
				AppClock.sched(rrand(0.2, 1), { this.writeDef; \write.debug; nil });
			}); 
		}, { 
			synthDef = nil; 
		})
	}

	
	sendDefToBundle { arg bundle, server;
		bundle.addPrepare([5, synthDef.asBytes]); //"/d_recv"
	}
	
	sendDef { arg server;
		synthDef.send(server);
	}

	writeDef {
		synthDef.writeDefFile;
	}
	
	name { ^synthDef.name }
	
	
}

SoundDefControl : SynthDefControl {
	sendDef { } //assumes that SoundDef does send to the same server 
	writeDef { }
	build { synthDef = source.synthDef; ^true }
	sendDefToBundle {}
}



CXPlayerControl : AbstractPlayControl {
	//here the lazy bus init happens and allocation of client side ressources
	//there is no bundle passing in build
	
	build { arg proxy;
		^source.prepareToPlayWithProxy(proxy)
	}
	
	playToBundle { arg bundle, extraArgs, proxy, addAction=\addToTail;
		var group;
		if(source.isPlaying, { source.stopToBundle(bundle) });
			//we'll need channel offset maybe.
		group = Group.newToBundle(bundle, proxy.asTarget, addAction);
		
		source.prepareToBundle(group, bundle);
		if(source.patchOut.isNil, { source.makePatchOut(group,true,proxy.asBus,bundle) });
		source.spawnToBundle(bundle);
		^source.synth;
		
	}
	stopToBundle { arg bundle;
		source.stopToBundle(bundle);
	}
	
	stop {
		source.stop;
	}
	play {
		source.play;
	}
	
	free { 
		source.free;
	}

}

CXSynthPlayerControl : CXPlayerControl {
	
	stopToBundle { arg bundle, fadeTime=0.5;
			source.releaseAndFreeToBundle(fadeTime, bundle); //free it each time for now.
			 
	}
	
	freeToBundle { }
	
}

