import Enums.ClientState;
import Enums.RequestStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ServerHelper {

    private ReliableUDP reliableUDP;
    private ServerPacketContentCreator packetContentCreator;
    private HashMap<String, String> pendingGameRequestsBySender;
    private HashMap<String, ClientModel> clients;
    private HashMap<String, GameBoard> games;

    public ServerHelper() {
        reliableUDP = new ReliableUDP();
        packetContentCreator = new ServerPacketContentCreator();

        pendingGameRequestsBySender = new HashMap<String, String>();
        clients = new HashMap<String, ClientModel>();
        games = new HashMap<String, GameBoard>();
    }

    public void Login(String clientName, int clientPort) throws IOException {
        boolean isValidName = !IsLoggedIn(clientName);

        if (isValidName) {
            ClientModel model = new ClientModel(clientName, clientPort);
            clients.put(clientName, model);
        }

        String message = packetContentCreator.AckLogin(isValidName);
        SendToClient(clientPort, message);
    }

    public void Logout(String clientName) throws IOException {
        // remove pending game requests from this client
        pendingGameRequestsBySender.remove(clientName);

        // update pending game requests to this client to 'failed'
        for (Map.Entry<String, String> kvp : pendingGameRequestsBySender.entrySet()) {
            if (kvp.getValue().equals(clientName)) {
                String clientThatRequestedGame = kvp.getKey();
                clients.get(clientThatRequestedGame).State = ClientState.Free;
                String message = packetContentCreator.AckChoose(clientName, RequestStatus.Failed);
                SendToClient(clientThatRequestedGame, message);
            }
        }

        //TODO: forfeit active games

        // remove the client
        clients.remove(clientName);
    }

    public void ListOtherClients(String clientName) throws IOException {
        // we allow people to query list of clients even if they are not logged in
        // maybe a client wants to see who else is online before deciding to sign on

        ArrayList<ClientModel> otherClients = new ArrayList<ClientModel>();
        for (ClientModel client : clients.values()) {
            if (!client.Name.equals(clientName)) {
                otherClients.add(client);
            }
        }

        String message = packetContentCreator.ListClients(otherClients);
        SendToClient(clientName, message);
    }

    public void ChoosePlayer(String clientName, String otherClientName) throws IOException {
        // make sure client sending game request is free
        if (!IsLoggedIn(clientName) || clients.get(clientName).State != ClientState.Free) {
            String message = packetContentCreator.AckChoose(otherClientName, RequestStatus.Failed);
            SendToClient(clientName, message);
            return;
        }

        // make sure client receiving game request is free
        if (!IsLoggedIn(otherClientName) || clients.get(otherClientName).State != ClientState.Free) {
            String message = packetContentCreator.AckChoose(otherClientName, RequestStatus.Failed);
            SendToClient(clientName, message);
            return;
        }

        // both clients are now in 'decision' state
        clients.get(clientName).State = ClientState.Decision;
        clients.get(otherClientName).State = ClientState.Decision;

        // send game request to other player
        String message = packetContentCreator.GameRequest(clientName);
        SendToClient(otherClientName, message);

        // add pending game request so we can validate AckChoose
        pendingGameRequestsBySender.put(clientName, otherClientName);
    }

    public void AckChoose(String clientName, String otherClientName, RequestStatus status) throws IOException {
        // make sure there is a pending game request
        if (!IsPendingGameRequest(otherClientName, clientName)) {
            // just do nothing, client is acknowledging a game request that doesn't exist
            return;
        }

        // now we know both clients are logged in and there is a pending game request

        // update state of both clients to busy or free
        if (status == RequestStatus.Accepted) {
            clients.get(clientName).State = ClientState.Busy;
            clients.get(otherClientName).State = ClientState.Busy;

            GameBoard newGame = new GameBoard();
            games.put(clientName, newGame);
            games.put(otherClientName, newGame);
        }
        else {
            clients.get(clientName).State = ClientState.Free;
            clients.get(otherClientName).State = ClientState.Free;
        }

        // inform original client of request status
        String message = packetContentCreator.AckChoose(clientName, status);
        SendToClient(otherClientName, message);
    }

    // decide whether game is over and who won/lost
    public void IsGameOver() {

    }

    // decide whether client move is valid
    public void IsValidMove() {

    }

    // decide who's turn it is to play
    public void NextClientTurn() {

    }

    // if client sends request to play with another client, reject new
    // requests to both of those clients until someone accepts/denies




    private boolean IsPendingGameRequest(String fromClient, String toClient) {
        return pendingGameRequestsBySender.containsKey(fromClient)
                && pendingGameRequestsBySender.get(fromClient).equals(toClient);
    }

    private boolean IsLoggedIn(String clientName) {
        return clients.containsKey(clientName);
    }

    private void SendToClient(int clientPort, String message) throws IOException {
        // client ip is hardcoded as server's ip, see --> https://piazza.com/class#spring2013/csee4119/69
        String clientIP = InetAddress.getLocalHost().getHostAddress();
        reliableUDP.Send(clientIP, clientPort, message);
    }

    private void SendToClient(String clientName, String message) throws IOException {
        //TODO: check for login?
        int clientPort = clients.get(clientName).Port;
        SendToClient(clientPort, message);
    }


}