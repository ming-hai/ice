// **********************************************************************
//
// Copyright (c) 2003-2007 ZeroC, Inc. All rights reserved.
//
// This copy of Ice is licensed to you under the terms described in the
// ICE_LICENSE file included in this distribution.
//
// **********************************************************************

package IceInternal;

final class UdpTransceiver implements Transceiver
{
    public java.nio.channels.SelectableChannel
    fd()
    {
        assert(_fd != null);
        return _fd;
    }

    public synchronized void
    close()
    {
	//
	// NOTE: closeSocket() may have already been invoked by shutdownReadWrite().
	//
	closeSocket();

	if(_readSelector != null)
	{
	    try
	    {
		_readSelector.close();
	    }
	    catch(java.io.IOException ex)
	    {
		// Ignore.
	    }
	    _readSelector = null;
	}
    }

    public void
    shutdownWrite()
    {
	//
	// NOTE: DatagramSocket does not support shutdownOutput.
	//
    }

    public synchronized void
    shutdownReadWrite()
    {
	//
	// NOTE: DatagramSocket does not support shutdownInput, and we
	// cannot use the C++ technique of sending a "wakeup" packet to
	// this socket because the Java implementation deadlocks when we
	// call disconnect() while receive() is in progress. Therefore
	// we close the socket here and wake up the selector.
	//
	closeSocket();

	if(_readSelector != null)
	{
	    _readSelector.wakeup();
	}
    }

    public void
    write(BasicStream stream, int timeout) // NOTE: timeout is not used
	throws LocalExceptionWrapper
    {
        java.nio.ByteBuffer buf = stream.prepareWrite();

        assert(buf.position() == 0);
        final int packetSize = java.lang.Math.min(_maxPacketSize, _sndSize - _udpOverhead);
        if(packetSize < buf.limit())
	{
	    //
	    // We don't log a warning here because the client gets an exception anyway.
	    //
	    throw new Ice.DatagramLimitException();
	}

        while(buf.hasRemaining())
        {
            try
            {
                assert(_fd != null);
                int ret = _fd.write(buf);

                if(_traceLevels.network >= 3)
                {
                    String s = "sent " + ret + " bytes via udp\n" + toString();
                    _logger.trace(_traceLevels.networkCat, s);
                }

                if(_stats != null)
                {
                    _stats.bytesSent(type(), ret);
                }

                assert(ret == buf.limit());
                break;
            }
	    catch(java.nio.channels.AsynchronousCloseException ex)
	    {
                Ice.ConnectionLostException se = new Ice.ConnectionLostException();
                se.initCause(ex);
                throw se;
	    }
            catch(java.net.PortUnreachableException ex)
            {
                Ice.ConnectionLostException se = new Ice.ConnectionLostException();
                se.initCause(ex);
                throw se;
            }
            catch(java.io.InterruptedIOException ex)
            {
                continue;
            }
            catch(java.io.IOException ex)
            {
                Ice.SocketException se = new Ice.SocketException();
                se.initCause(ex);
                throw se;
            }
        }
    }

    public boolean
    read(BasicStream stream, int timeout) // NOTE: timeout is not used
    {
	assert(stream.pos() == 0);

	final int packetSize = java.lang.Math.min(_maxPacketSize, _rcvSize - _udpOverhead);
	if(packetSize < stream.size())
	{
	    //
	    // We log a warning here because this is the server side -- without the
	    // the warning, there would only be silence.
	    //
	    if(_warn)
	    {
		_logger.warning("DatagramLimitException: maximum size of " + packetSize + " exceeded");
	    }
	    throw new Ice.DatagramLimitException();
	}
        stream.resize(packetSize, true);
        java.nio.ByteBuffer buf = stream.prepareRead();
        buf.position(0);

	synchronized(this)
	{
	    assert(_fd != null);
	    if(_readSelector == null)
	    {
		try
		{
		    _readSelector = java.nio.channels.Selector.open();
		    _fd.register(_readSelector, java.nio.channels.SelectionKey.OP_READ, null);
		}
		catch(java.io.IOException ex)
		{
		    if(Network.connectionLost(ex))
		    {
			Ice.ConnectionLostException se = new Ice.ConnectionLostException();
			se.initCause(ex);
			throw se;
		    }

		    Ice.SocketException se = new Ice.SocketException();
		    se.initCause(ex);
		    throw se;
		}
	    }
	}

        int ret = 0;
        while(true)
        {
	    //
	    // Check for shutdown.
	    //
	    java.nio.channels.DatagramChannel fd = null;
	    synchronized(this)
	    {
		if(_fd == null)
		{
		    throw new Ice.ConnectionLostException();
		}
		fd = _fd;
	    }

	    try
	    {
		java.net.InetSocketAddress sender = (java.net.InetSocketAddress)fd.receive(buf);
		if(sender == null || buf.position() == 0)
		{
		    //
		    // Wait until packet arrives or socket is closed.
		    //
		    _readSelector.select();
		    continue;
		}

		ret = buf.position();

		if(_connect)
		{
		    //
		    // If we must connect, then we connect to the first peer that
		    // sends us a packet.
		    //
		    Network.doConnect(fd, sender, -1);
		    _connect = false; // We're connected now

		    if(_traceLevels.network >= 1)
		    {
			String s = "connected udp socket\n" + toString();
			_logger.trace(_traceLevels.networkCat, s);
		    }
		}

		break;
	    }
	    catch(java.nio.channels.AsynchronousCloseException ex)
	    {
                Ice.ConnectionLostException se = new Ice.ConnectionLostException();
                se.initCause(ex);
                throw se;
	    }
            catch(java.net.PortUnreachableException ex)
            {
                Ice.ConnectionLostException se = new Ice.ConnectionLostException();
                se.initCause(ex);
                throw se;
            }
	    catch(java.io.InterruptedIOException ex)
	    {
		continue;
	    }
	    catch(java.io.IOException ex)
	    {
		if(Network.connectionLost(ex))
		{
		    Ice.ConnectionLostException se = new Ice.ConnectionLostException();
		    se.initCause(ex);
		    throw se;
		}

		Ice.SocketException se = new Ice.SocketException();
		se.initCause(ex);
		throw se;
	    }
        }

        if(_traceLevels.network >= 3)
        {
            String s = "received " + ret + " bytes via udp\n" + toString();
            _logger.trace(_traceLevels.networkCat, s);
        }

        if(_stats != null)
        {
            _stats.bytesReceived(type(), ret);
        }

        stream.resize(ret, true);
        stream.pos(ret);

	return false;
    }

    public String
    type()
    {
        return "udp";
    }

    public String
    toString()
    {
        return Network.fdToString(_fd);
    }

    public void
    checkSendSize(BasicStream stream, int messageSizeMax)
    {
	if(stream.size() > messageSizeMax)
	{
	    throw new Ice.MemoryLimitException();
	}
        final int packetSize = java.lang.Math.min(_maxPacketSize, _sndSize - _udpOverhead);
	if(packetSize < stream.size())
	{
	    throw new Ice.DatagramLimitException();
	}
    }

    public final boolean
    equivalent(String host, int port)
    {
        java.net.InetSocketAddress addr = Network.getAddress(host, port);
        return addr.equals(_addr);
    }

    public final int
    effectivePort()
    {
        return _addr.getPort();
    }

    //
    // Only for use by UdpEndpoint
    //
    UdpTransceiver(Instance instance, String host, int port)
    {
        _traceLevels = instance.traceLevels();
        _logger = instance.initializationData().logger;
        _stats = instance.initializationData().stats;
        _incoming = false;
        _connect = true;
	_warn = instance.initializationData().properties.getPropertyAsInt("Ice.Warn.Datagrams") > 0;

        try
        {
            _fd = Network.createUdpSocket();
	    setBufSize(instance);
	    Network.setBlock(_fd, false);
            _addr = Network.getAddress(host, port);
            Network.doConnect(_fd, _addr, -1);
            _connect = false; // We're connected now

            if(_traceLevels.network >= 1)
            {
                String s = "starting to send udp packets\n" + toString();
                _logger.trace(_traceLevels.networkCat, s);
            }
        }
        catch(Ice.LocalException ex)
        {
            _fd = null;
            throw ex;
        }
    }

    //
    // Only for use by UdpEndpoint
    //
    UdpTransceiver(Instance instance, String host, int port, boolean connect)
    {
        _traceLevels = instance.traceLevels();
        _logger = instance.initializationData().logger;
        _stats = instance.initializationData().stats;
        _incoming = true;
        _connect = connect;
	_warn = instance.initializationData().properties.getPropertyAsInt("Ice.Warn.Datagrams") > 0;

        try
        {
            _fd = Network.createUdpSocket();
	    setBufSize(instance);
	    Network.setBlock(_fd, false);
            _addr = new java.net.InetSocketAddress(host, port);
	    if(_traceLevels.network >= 2)
	    {
		String s = "attempting to bind to udp socket " + Network.addrToString(_addr);
		_logger.trace(_traceLevels.networkCat, s);
	    }
            _addr = Network.doBind(_fd, _addr);

            if(_traceLevels.network >= 1)
            {
                String s = "starting to receive udp packets\n" + toString();
                _logger.trace(_traceLevels.networkCat, s);
            }
        }
        catch(Ice.LocalException ex)
        {
            _fd = null;
            throw ex;
        }
    }

    private synchronized void
    setBufSize(Instance instance)
    {
        assert(_fd != null);

	for(int i = 0; i < 2; ++i)
	{
	    String direction;
	    String prop;
	    int dfltSize;
	    if(i == 0)
	    {
		direction = "receive";
		prop = "Ice.UDP.RcvSize";
		dfltSize = Network.getRecvBufferSize(_fd);
		_rcvSize = dfltSize;
	    }
	    else
	    {
		direction = "send";
		prop = "Ice.UDP.SndSize";
		dfltSize = Network.getSendBufferSize(_fd);
		_sndSize = dfltSize;
	    }

	    //
	    // Get property for buffer size and check for sanity.
	    //
	    int sizeRequested = instance.initializationData().properties.getPropertyAsIntWithDefault(prop, dfltSize);
	    if(sizeRequested < _udpOverhead)
	    {
		_logger.warning("Invalid " + prop + " value of " + sizeRequested + " adjusted to " + dfltSize);
		sizeRequested = dfltSize;
	    }
		
	    if(sizeRequested != dfltSize)
	    {
		//
		// Try to set the buffer size. The kernel will silently adjust
		// the size to an acceptable value. Then read the size back to
		// get the size that was actually set.
		//
		int sizeSet;
		if(i == 0)
		{
		    Network.setRecvBufferSize(_fd, sizeRequested);
		    _rcvSize = Network.getRecvBufferSize(_fd);
		    sizeSet = _rcvSize;
		}
		else
		{
		    Network.setSendBufferSize(_fd, sizeRequested);
		    _sndSize = Network.getSendBufferSize(_fd);
		    sizeSet = _sndSize;
		}

		//
		// Warn if the size that was set is less than the requested size.
		//
		if(sizeSet < sizeRequested)
		{
		    _logger.warning("UDP " + direction + " buffer size: requested size of "
			            + sizeRequested + " adjusted to " + sizeSet);
		}
	    }
	}
    }

    private void
    closeSocket()
    {
        if(_fd != null)
	{
	    if(_traceLevels.network >= 1)
	    {
		String s = "closing udp connection\n" + toString();
		_logger.trace(_traceLevels.networkCat, s);
	    }

	    try
	    {
		_fd.close();
	    }
	    catch(java.io.IOException ex)
	    {
	    }
	    _fd = null;
	}
    }

    protected synchronized void
    finalize()
        throws Throwable
    {
        IceUtil.Assert.FinalizerAssert(_fd == null);

        super.finalize();
    }

    private TraceLevels _traceLevels;
    private Ice.Logger _logger;
    private Ice.Stats _stats;
    private boolean _incoming;
    private boolean _connect;
    private final boolean _warn;
    private int _rcvSize;
    private int _sndSize;
    private java.nio.channels.DatagramChannel _fd;
    private java.net.InetSocketAddress _addr;
    private java.nio.channels.Selector _readSelector;

    //
    // The maximum IP datagram size is 65535. Subtract 20 bytes for the IP header and 8 bytes for the UDP header
    // to get the maximum payload.
    //
    private final static int _udpOverhead = 20 + 8;
    private final static int _maxPacketSize = 65535 - _udpOverhead;
}
