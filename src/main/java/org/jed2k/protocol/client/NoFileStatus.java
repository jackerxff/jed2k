package org.jed2k.protocol.client;

import org.jed2k.exception.JED2KException;
import org.jed2k.protocol.Dispatchable;
import org.jed2k.protocol.Dispatcher;
import org.jed2k.protocol.Hash;

public class NoFileStatus extends Hash implements Dispatchable {

    public NoFileStatus(Hash hash) {
        super(hash);
    }
    
    public NoFileStatus() {
        super();
    }
    
    @Override
    public void dispatch(Dispatcher dispatcher) throws JED2KException {
        dispatcher.onClientNoFileStatus(this);        
    }
    
}
