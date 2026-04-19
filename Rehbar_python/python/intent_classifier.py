from sklearn.naive_bayes import MultinomialNB
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.pipeline import Pipeline

class IntentClassifier:
    def __init__(self):
        self.model = Pipeline([
            ('tfidf', TfidfVectorizer()),  # Convert text to numbers
            ('clf', MultinomialNB())        # Classify the numbers
        ])

    def train(self):
        # Training data - expanded for higher accuracy
        data = [
            # OPEN_APP
            ('open chrome', 'OPEN_APP'), ('launch notepad', 'OPEN_APP'), ('start spotify', 'OPEN_APP'),
            ('run calculator', 'OPEN_APP'), ('execute vlc', 'OPEN_APP'), ('fire up the browser', 'OPEN_APP'),
            ('can you open excel', 'OPEN_APP'), ('please start word', 'OPEN_APP'), ('launch my music player', 'OPEN_APP'),
            ('open terminal', 'OPEN_APP'), ('start the camera', 'OPEN_APP'), ('run intellij', 'OPEN_APP'),

            # CLOSE_APP
            ('close chrome', 'CLOSE_APP'), ('stop notepad', 'CLOSE_APP'), ('exit spotify', 'CLOSE_APP'),
            ('shut down calculator', 'CLOSE_APP'), ('kill the process', 'CLOSE_APP'), ('quit vlc', 'CLOSE_APP'),
            ('close the browser', 'CLOSE_APP'), ('terminate the app', 'CLOSE_APP'), ('stop running word', 'CLOSE_APP'),

            # CREATE_FILE
            ('create folder named Projects', 'CREATE_FILE'), ('make a new directory', 'CREATE_FILE'),
            ('new folder called notes', 'CREATE_FILE'), ('generate a file named test', 'CREATE_FILE'),
            ('create a new text file', 'CREATE_FILE'), ('make directory backup', 'CREATE_FILE'),
            ('build a new folder', 'CREATE_FILE'), ('create file index.html', 'CREATE_FILE'),

            # DELETE_FILE
            ('delete the file homework', 'DELETE_FILE'), ('remove folder old stuff', 'DELETE_FILE'),
            ('erase file temp', 'DELETE_FILE'), ('trash this folder', 'DELETE_FILE'),
            ('delete my resume', 'DELETE_FILE'), ('remove the directory data', 'DELETE_FILE'),
            ('wipe the file log', 'DELETE_FILE'), ('get rid of old folder', 'DELETE_FILE'),

            # RENAME_FILE
            ('rename file report to final', 'RENAME_FILE'), ('change name of folder to work', 'RENAME_FILE'),
            ('rename this to backup', 'RENAME_FILE'), ('modify file name to notes', 'RENAME_FILE'),
            ('update folder name to projects', 'RENAME_FILE'),

            # WEB_SEARCH
            ('search for python tutorials', 'WEB_SEARCH'), ('google the weather', 'WEB_SEARCH'),
            ('lookup java documentation', 'WEB_SEARCH'), ('search on the web for news', 'WEB_SEARCH'),
            ('find information about space', 'WEB_SEARCH'), ('search youtube for music', 'WEB_SEARCH'),
            ('who is the president', 'WEB_SEARCH'), ('how to cook pasta', 'WEB_SEARCH'),

            # OPEN_SITE
            ('open youtube', 'OPEN_SITE'), ('go to github', 'OPEN_SITE'), ('navigate to gmail', 'OPEN_SITE'),
            ('visit stackoverflow', 'OPEN_SITE'), ('open facebook', 'OPEN_SITE'), ('go to my mail', 'OPEN_SITE'),
            ('open the website wikipedia', 'OPEN_SITE'), ('browse to linkedin', 'OPEN_SITE'),

            # SET_ALARM
            ('set alarm for 7 am', 'SET_ALARM'), ('remind me at 3pm to drink water', 'SET_ALARM'),
            ('wake me up at 6 o clock', 'SET_ALARM'), ('set a timer for 10 minutes', 'SET_ALARM'),
            ('new alarm for 8:30', 'SET_ALARM'), ('create a reminder for noon', 'SET_ALARM'),

            # SYSTEM_INFO
            ('what time is it', 'SYSTEM_INFO'), ('what is the date', 'SYSTEM_INFO'),
            ('show battery level', 'SYSTEM_INFO'), ('how much ram is used', 'SYSTEM_INFO'),
            ('current cpu usage', 'SYSTEM_INFO'), ('check my system status', 'SYSTEM_INFO'),
            ('tell me the time', 'SYSTEM_INFO'), ('what day is it today', 'SYSTEM_INFO')
        ]

        # Unpack the list of tuples into phrases and labels
        phrases, labels = zip(*data)

        self.model.fit(list(phrases), list(labels))
        print('Intent classifier trained successfully with expanded data')

    def predict(self, text):
        return self.model.predict([text])[0]
